package com.chomugiri.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chomugiri.app.core.*
import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.TerminalClient
import com.chomugiri.app.net.VercelClient
import com.chomugiri.app.service.BuildService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val artifacts: StateFlow<List<Artifact>> = _artifacts.asStateFlow()

    private val _activeArtifactId = MutableStateFlow<String?>(null)
    val activeArtifactId: StateFlow<String?> = _activeArtifactId.asStateFlow()

    /**
     * Which conversations currently have a pipeline/chat job running. Keyed by conversation id
     * (not a single global flag) so one chat can keep working while the user switches to and
     * sends in another — matching how every other multi-chat AI app behaves.
     */
    private val jobs = mutableMapOf<String, Job>()
    private val _busyConversations = MutableStateFlow<Set<String>>(emptySet())
    val busyConversations: StateFlow<Set<String>> = _busyConversations.asStateFlow()

    /**
     * Every mutation of `jobs` goes through here, so the foreground service is started exactly
     * while work is in flight and stopped the moment the last job ends. Android throttles a
     * backgrounded app's network and can kill the process outright, which is what was silently
     * killing runs when you left the app mid-build.
     */
    private fun syncBusy(status: String? = null) {
        _busyConversations.value = jobs.keys.toSet()
        // The terminal agent counts as work in flight too. It runs on viewModelScope rather than
        // as a conversation job, so it was invisible here — and an APK compile now starts as the
        // pipeline job is being removed, which meant the service stopped while the longest part of
        // the whole flow was still running, with Android free to kill the process.
        if (jobs.isEmpty() && !_agentRunning.value) BuildService.stop(getApplication())
        else BuildService.start(getApplication(), status ?: "Working...")
    }

    /** Live output of the terminal build agent. Capped so a long run can't grow this unbounded. */
    private val _agentLog = MutableStateFlow("")
    val agentLog: StateFlow<String> = _agentLog.asStateFlow()
    private val MAX_AGENT_LOG = 100_000

    private fun appendAgentLog(text: String) {
        val next = _agentLog.value + text
        _agentLog.value = if (next.length > MAX_AGENT_LOG) next.takeLast(MAX_AGENT_LOG) else next
    }

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    /** Non-null while a run is waiting on the user to approve its plan. */
    private val _pendingPermission = MutableStateFlow<AgentPermissionRequest?>(null)
    val pendingPermission: StateFlow<AgentPermissionRequest?> = _pendingPermission.asStateFlow()

    private val _deployState = MutableStateFlow<Map<String, DeployUiState>>(emptyMap())
    val deployState: StateFlow<Map<String, DeployUiState>> = _deployState.asStateFlow()

    private var autoConnectJob: Job? = null
    private var loaded = false

    /**
     * Whether the UI is actually on screen. A "build finished" notification is useful when the
     * user is off in another app; firing one while they are watching the result appear is noise.
     */
    private var uiVisible = true
    fun onUiVisible(visible: Boolean) { uiVisible = visible }

    private fun notifyIfAway(title: String, detail: String) {
        if (!uiVisible) BuildService.notifyFinished(getApplication(), title, detail)
    }

    init {
        viewModelScope.launch {
            store.settings.collect { raw ->
                // Rewrites model ids that are dead on their endpoint (see DEAD_MODEL_REPLACEMENTS).
                // A saved config outranks the default forever, so without this a user already
                // holding z-ai/glm-5.3 keeps hitting 404 no matter what the shipped default says.
                val s = raw.migrated()
                _settings.value = s
                if (s != raw) viewModelScope.launch { store.saveSettings(s) }
                if (!loaded) {
                    loaded = true
                    if (s.autoConnectTerminal && s.terminalUrl.isNotBlank()) startAutoConnect()
                }
            }
        }
        viewModelScope.launch {
            var first = true
            store.conversations.collect { list ->
                if (first) {
                    // Nothing can still be streaming at startup — no job survives a process
                    // death. Without this, a run Android killed in the background left its
                    // message spinning "Thinking..." forever, with no way to retry it.
                    _conversations.value = list.map { c ->
                        if (c.messages.none { it.streaming }) c
                        else c.copy(messages = c.messages.map { m ->
                            if (!m.streaming) m else m.copy(
                                streaming = false,
                                error = m.error ?: "Interrupted — Android stopped this run while the app was in the background. Send it again.",
                            )
                        })
                    }
                    if (_activeConversationId.value == null) {
                        _activeConversationId.value = _conversations.value.maxByOrNull { it.updatedAt }?.id
                    }
                    first = false
                }
            }
        }
        viewModelScope.launch {
            var first = true
            store.artifacts.collect { list ->
                if (first) { _artifacts.value = list; first = false }
            }
        }
    }

    // ---------- settings ----------

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        _settings.value = next
        viewModelScope.launch { store.saveSettings(next) }
    }

    // ---------- custom models ----------

    fun addCustomModel(): String {
        val id = UUID.randomUUID().toString()
        updateSettings { it.copy(customModels = it.customModels + CustomModel(id = id, name = "New model")) }
        return id
    }

    fun updateCustomModel(id: String, block: (CustomModel) -> CustomModel) {
        updateSettings { s -> s.copy(customModels = s.customModels.map { if (it.id == id) block(it) else it }) }
    }

    fun deleteCustomModel(id: String) {
        updateSettings { it.copy(customModels = it.customModels.filterNot { m -> m.id == id }) }
    }

    // ---------- conversations ----------

    val activeConversation: Conversation?
        get() = _conversations.value.firstOrNull { it.id == _activeConversationId.value }

    /**
     * History for a specific conversation, never "whichever one is on screen right now". A job
     * outlives the user's attention: start a build in one chat, switch to another while it runs,
     * and reading activeConversation here fed the AI the *other* chat's messages as context.
     */
    private fun conversationById(id: String): Conversation? =
        _conversations.value.firstOrNull { it.id == id }

    fun newConversation() {
        _activeConversationId.value = null
    }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
    }

    fun deleteConversation(id: String) {
        // A job outlives the chat it belongs to unless it is stopped here: without this, deleting
        // a chat mid-build left the pipeline running, still writing into a conversation the user
        // can no longer see and holding it in busyConversations forever.
        jobs[id]?.cancel()
        jobs.remove(id)
        syncBusy()
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_activeConversationId.value == id) {
            _activeConversationId.value = _conversations.value.maxByOrNull { it.updatedAt }?.id
        }
        persistConversations()
    }

    private fun persistConversations() {
        val snapshot = _conversations.value
        viewModelScope.launch { store.saveConversations(snapshot) }
    }

    private fun persistArtifacts() {
        val snapshot = _artifacts.value
        viewModelScope.launch { store.saveArtifacts(snapshot) }
    }

    private fun ensureConversation(firstUserText: String): String {
        _activeConversationId.value?.let { return it }
        val id = UUID.randomUUID().toString()
        val convo = Conversation(id = id, title = firstUserText.take(48).ifBlank { "New chat" })
        _conversations.value = _conversations.value + convo
        _activeConversationId.value = id
        return id
    }

    private fun mutateConversation(id: String, block: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map {
            if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it
        }
    }

    private fun addMessage(convId: String, msg: Message) =
        mutateConversation(convId) { it.copy(messages = it.messages + msg) }

    private fun updateMessage(convId: String, msgId: String, block: (Message) -> Message) =
        mutateConversation(convId) { c ->
            c.copy(messages = c.messages.map { if (it.id == msgId) block(it) else it })
        }

    fun renameConversation(convId: String, title: String) =
        mutateConversation(convId) { it.copy(title = title) }

    /** Forks a chat at a given message into a brand-new conversation, keeping history up to it. */
    fun branchConversation(convId: String, uptoMessageId: String): String? {
        val source = _conversations.value.firstOrNull { it.id == convId } ?: return null
        val cut = source.messages.indexOfFirst { it.id == uptoMessageId }
        if (cut < 0) return null
        val kept = source.messages.subList(0, cut + 1).map { it.copy(id = UUID.randomUUID().toString()) }
        val newId = UUID.randomUUID().toString()
        val branch = Conversation(id = newId, title = "${source.title} (branch)", messages = kept)
        _conversations.value = _conversations.value + branch
        _activeConversationId.value = newId
        persistConversations()
        return newId
    }

    // ---------- the one entry point the UI calls ----------

    /**
     * @param forcedRole Power-user override (long-press send): skip the router entirely and let
     * this one message go straight to the given role as a direct chat call.
     * @param forcedCustomModelId Same idea, but for a user-added custom model (see
     * AppSettings.customModels) instead of one of the five fixed pipeline roles.
     */
    fun send(text: String, forcedIntent: Intent? = null, forcedRole: RoleKey? = null, forcedCustomModelId: String? = null) {
        if (text.isBlank()) return
        // Only refuse if THIS conversation already has a job running — a different, idle
        // conversation must stay free to send while another one is mid-pipeline.
        _activeConversationId.value?.let { if (jobs.containsKey(it)) return }
        val convId = ensureConversation(text)
        val s = _settings.value

        val userMsgId = UUID.randomUUID().toString()
        addMessage(convId, Message(id = userMsgId, role = "user", content = text))

        val assistantId = UUID.randomUUID().toString()
        // Placeholder — its real kind/modelUsed land once the AI triage call below actually
        // decides. Renders as a plain "Thinking..." shimmer in the meantime.
        addMessage(convId, Message(id = assistantId, role = "assistant", streaming = true))

        val startedAt = System.currentTimeMillis()
        // Someone who just tapped Stop does not need a notification telling them it stopped.
        var stoppedByUser = false
        val job = viewModelScope.launch {
            try {
                // The router is a real AI decision (the Fast Chat role), not a keyword list — that
                // is what stops "what's the capital of France" from ever waking the coding swarm.
                val customModel = forcedCustomModelId?.let { id -> s.customModels.firstOrNull { it.id == id } }
                val intent = if (forcedRole != null || customModel != null) Intent.CHAT else (forcedIntent ?: classifyIntentAi(text, s))
                updateMessage(convId, assistantId) {
                    it.copy(
                        kind = when (intent) {
                            Intent.CHAT -> "chat"
                            Intent.PIPELINE -> "pipeline"
                            Intent.RESEARCH -> "research"
                            Intent.PPTX -> "pipeline"
                            Intent.VIDEO -> "pipeline"
                        },
                        modelUsed = when {
                            customModel != null -> customModel.name.ifBlank { customModel.model }
                            forcedRole != null -> ROLE_LABELS[forcedRole]
                            intent == Intent.CHAT -> ROLE_LABELS[RoleKey.FAST]
                            intent == Intent.RESEARCH -> "Deep Research"
                            intent == Intent.PPTX -> "PPTX Generator"
                            intent == Intent.VIDEO -> "Video Generator"
                            else -> null
                        },
                    )
                }
                when (intent) {
                    Intent.CHAT -> if (customModel != null) {
                        val provider = ProviderConfig(apiKey = customModel.apiKey, baseUrl = customModel.baseUrl, model = customModel.model)
                        runFastChat(convId, assistantId, text, provider, customModel.name.ifBlank { customModel.model })
                    } else {
                        val role = forcedRole ?: RoleKey.FAST
                        runFastChat(convId, assistantId, text, s.provider(role), ROLE_LABELS[role] ?: role.name, role)
                    }
                    Intent.PIPELINE -> {
                        // Someone asking for an APK with no terminal connected cannot be served:
                        // the compile happens on their machine, and nothing this app does can
                        // substitute for that. Running the whole pipeline first and only then
                        // admitting it wastes minutes of their time and their token budget, so
                        // the check happens before any expensive call — and the answer comes back
                        // in whatever language they actually wrote in.
                        if (wantsApk(text) && !canBuildApk()) {
                            explainTerminalOffline(convId, assistantId, text)
                            return@launch
                        }
                        // Real conversation history, not just this one isolated message — without
                        // it Kimi can't tell "make a website" -> its own clarifying question ->
                        // the user's answer is one continuous exchange, and would just ask again.
                        val history = (conversationById(convId)?.messages ?: emptyList())
                            .filter { it.content.isNotBlank() && it.id != userMsgId && it.id != assistantId }
                            .takeLast(8)
                            .map { ChatTurn(it.role, it.content) }
                        // A follow-up in a conversation that already built something edits that
                        // project — without this every message, even "add dark mode", started a
                        // brand-new project from scratch and the coder never saw its own prior work.
                        val existingArtifactId = (conversationById(convId)?.messages ?: emptyList())
                            .lastOrNull { it.artifactId != null }?.artifactId
                        val existingFiles = existingArtifactId
                            ?.let { id -> _artifacts.value.firstOrNull { it.id == id }?.files }
                            ?: emptyList()
                        consume(
                            convId, assistantId,
                            runPipeline(
                                text, s, history,
                                onConfirmPlan = { plan -> askPermission("plan", plan) },
                                existingFiles = existingFiles,
                            ),
                            text,
                            initialArtifactId = existingArtifactId,
                        )
                    }
                    Intent.RESEARCH -> consume(convId, assistantId, runDeepResearch(text, s), text)
                    Intent.PPTX -> consume(convId, assistantId, runPptxPipeline(text, s), text)
                    Intent.VIDEO -> consume(convId, assistantId, runVideoPipeline(text, s), text)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stop was tapped (or the chat was deleted). This is not a failure, and it must not
                // render as one — CancellationException carries a message like "StandaloneCoroutine
                // was cancelled", which was being shown to the user verbatim in a red Build-failed
                // banner. Leave whatever partial content arrived, note that it was stopped.
                updateMessage(convId, assistantId) { m ->
                    m.copy(
                        streaming = false,
                        content = m.content.ifBlank { "Stopped." },
                        steps = m.steps.map { s -> if (s.done) s else s.copy(done = true) },
                    )
                }
                stoppedByUser = true
                throw e
            } catch (e: Exception) {
                updateMessage(convId, assistantId) {
                    it.copy(streaming = false, error = e.message ?: "Something went wrong.")
                }
            } finally {
                val finished = conversationById(convId)?.messages?.firstOrNull { it.id == assistantId }
                updateMessage(convId, assistantId) { it.copy(streaming = false) }
                jobs.remove(convId)
                syncBusy()
                // Announced once the job is off the books, so a still-running sibling keeps the
                // progress notification and only the actually-finished one reports.
                //
                // Gated on the run being one you'd plausibly walk away from: a build that produced
                // files, or anything that took long enough to leave the app over. Buzzing someone's
                // phone because a two-second "hi" finished while they were elsewhere is spam, and
                // spam is how people end up muting the channel that carries the useful alerts.
                finished?.let { m ->
                    val elapsed = System.currentTimeMillis() - startedAt
                    val worthTelling = !stoppedByUser &&
                        (m.artifactId != null || m.error != null || elapsed > 25_000)
                    if (worthTelling) {
                        val title = conversationById(convId)?.title ?: "ChomuGiri"
                        if (m.error != null) notifyIfAway("Build failed - " + title, m.error)
                        else notifyIfAway("Done - " + title, m.content.take(120).ifBlank { "Your project is ready." })
                    }
                }
                persistConversations()
            }
        }
        jobs[convId] = job
        syncBusy("Starting...")
    }

    /** Stops the given conversation's job, or the active one if none is given. */
    fun stop(convId: String? = null) {
        val id = convId ?: _activeConversationId.value ?: return
        jobs[id]?.cancel()
        jobs.remove(id)
        syncBusy()
    }

    private suspend fun runFastChat(
        convId: String, msgId: String, text: String, provider: ProviderConfig, label: String, role: RoleKey? = RoleKey.FAST,
    ) {
        val history = (conversationById(convId)?.messages ?: emptyList())
            .filter { it.kind == "chat" && it.content.isNotBlank() && it.id != msgId }
            .takeLast(10)
            .map { ChatTurn(it.role, it.content) }

        val systemPrompt = if (role == RoleKey.FAST) FAST_CHAT_SYSTEM_PROMPT else
            "You are $label, answering directly because the user picked you specifically for this message. Reply naturally and helpfully."
        val turns = listOf(ChatTurn("system", systemPrompt)) + history + ChatTurn("user", text)
        val sb = StringBuilder()
        // Throttled, not per-token. Every updateMessage rebuilds the whole conversation list and
        // recomposes the thread; a fast model emits tokens far quicker than a frame, so pushing
        // each one meant dozens of full rebuilds per frame and visible jank on a long reply.
        // 50ms is still ~20 visible updates a second, which reads as continuous typing.
        var lastPush = 0L
        LlmClient.stream(provider, label, turns, temperature = 0.6, maxTokens = 2048)
            .collect { chunk ->
                sb.append(chunk)
                val now = System.currentTimeMillis()
                if (now - lastPush >= STREAM_UI_INTERVAL_MS) {
                    lastPush = now
                    updateMessage(convId, msgId) { it.copy(content = sb.toString()) }
                }
            }
        // The throttle can swallow the final chunk, so the finished text is always written once.
        updateMessage(convId, msgId) { it.copy(content = sb.toString()) }
    }

    private suspend fun consume(
        convId: String,
        msgId: String,
        flow: kotlinx.coroutines.flow.Flow<PipelineEvent>,
        prompt: String,
        // Set when this run is a follow-up edit on a project this conversation already built —
        // keeps the run attached to that same Artifact instead of minting a brand-new one.
        initialArtifactId: String? = null,
    ) {
        val steps = mutableListOf<ThinkingStep>()
        val body = StringBuilder()
        var lastChunkPush = 0L
        var artifactId: String? = initialArtifactId
        val startedAt = System.currentTimeMillis()

        flow.collect { ev ->
            when (ev) {
                is PipelineEvent.Step -> {
                    // Keeps the shade useful while you are in another app: the notification
                    // tracks the actual stage rather than a static "Working...".
                    if (jobs.isNotEmpty()) BuildService.start(getApplication(), ev.text)
                    if (ev.done && steps.isNotEmpty() && !steps.last().done) {
                        steps[steps.lastIndex] = steps.last().copy(text = ev.text, done = true)
                    } else {
                        steps += ThinkingStep(ev.text, ev.done)
                        // The coder streams one step per line of its reasoning, so a large build
                        // could pile up hundreds — every one of them re-rendered in the bubble and
                        // serialized into DataStore on save. Oldest lines drop off; a normal run
                        // never reaches this, and the visible timeline is the recent part anyway.
                        while (steps.size > MAX_STEPS_PER_MESSAGE) steps.removeAt(0)
                    }
                    updateMessage(convId, msgId) { it.copy(steps = steps.toList()) }
                }

                is PipelineEvent.Chunk -> {
                    body.append(ev.text)
                    // Same throttle as runFastChat — see STREAM_UI_INTERVAL_MS. Done() always
                    // writes the finished body, so a swallowed final chunk cannot be lost.
                    val now = System.currentTimeMillis()
                    if (now - lastChunkPush >= STREAM_UI_INTERVAL_MS) {
                        lastChunkPush = now
                        updateMessage(convId, msgId) { it.copy(content = body.toString()) }
                    }
                }

                is PipelineEvent.Files -> {
                    val id = artifactId ?: UUID.randomUUID().toString()
                    val isNew = artifactId == null
                    artifactId = id
                    // Diffed against what the project held a moment ago, so the +/- counts shown
                    // in chat are the real change this turn made — not the file's total size.
                    val previousFiles = _artifacts.value.firstOrNull { it.id == id }?.files ?: emptyList()
                    val actions = fileActionsBetween(previousFiles, ev.files)
                    if (actions.isNotEmpty()) {
                        updateMessage(convId, msgId) { m ->
                            val merged = m.fileActions.associateBy { it.path }.toMutableMap()
                            actions.forEach { a ->
                                // A file created earlier this turn and revised by the audit loop
                                // stays "Created", with the counts rolled forward.
                                val prior = merged[a.path]
                                merged[a.path] = if (prior != null && prior.verb == "Created") {
                                    a.copy(verb = "Created", added = prior.added + a.added, removed = prior.removed + a.removed)
                                } else a
                            }
                            m.copy(fileActions = merged.values.toList())
                        }
                    }
                    // Never the raw prompt: this title is also what the deploy is named, so it
                    // becomes the public URL. Clean it locally now, refine with a real name after.
                    val existingTitle = _artifacts.value.firstOrNull { it.id == id }?.title
                    val title = existingTitle ?: cleanProjectName(prompt)
                    if (isNew) autoNameProject(id, convId, prompt)
                    val existing = _artifacts.value.firstOrNull { it.id == id }
                    val art = Artifact(
                        id, title, ev.files, existing?.createdAt ?: System.currentTimeMillis(),
                        pinned = existing?.pinned ?: false,
                    )
                    _artifacts.value = _artifacts.value.filterNot { it.id == id } + art
                    // Only steal the screen if the user is actually watching THIS chat and has
                    // nothing else open. A build running in a background conversation used to
                    // throw its project panel over whatever you were reading the moment files
                    // landed; the tappable file rows in chat are the way in instead.
                    if (_activeConversationId.value == convId && _activeArtifactId.value == null) {
                        _activeArtifactId.value = id
                    }
                    updateMessage(convId, msgId) { it.copy(artifactId = id) }
                    renameConversation(convId, title)
                }

                is PipelineEvent.Done -> {
                    // Flush whatever the throttle held back before deciding what to show.
                    if (body.isNotEmpty()) updateMessage(convId, msgId) { it.copy(content = body.toString()) }
                    if (body.isEmpty() && ev.files.isNotEmpty()) {
                        // Something readable immediately, so the bubble is never blank while the
                        // real summary is still being written.
                        updateMessage(convId, msgId) {
                            it.copy(content = "Done — ${ev.files.size} file(s) ready.")
                        }
                        summariseBuild(convId, msgId, prompt, ev.files)
                    }
                    artifactId?.let { id ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        _artifacts.value = _artifacts.value.map {
                            if (it.id == id) it.copy(
                                buildMs = elapsed,
                                // The mode's real audit count, not the retired maxAuditLoops
                                // slider — that field is now only a migration fallback, so the
                                // badge on the project was reporting a number no run ever used.
                                auditRounds = _settings.value.mode().audits,
                                resolvedBy = ev.resolvedBy.ifBlank { null } ?: it.resolvedBy,
                                lastAuditIssues = ev.auditIssues.ifEmpty { it.lastAuditIssues },
                            ) else it
                        }
                    }
                    persistArtifacts()
                    // A freshly built website deploys itself — no manual Deploy tap needed. Only
                    // does anything when a Vercel token is set and the project is a real website.
                    artifactId?.let { autoDeployAndAnnounce(it) }
                    // ...and a freshly built Android project compiles itself, for the same reason.
                    // Asking for an app and being handed Kotlin source files is not finishing the
                    // job; the APK is the thing that was actually wanted.
                    artifactId?.let { id ->
                        val art = _artifacts.value.firstOrNull { it.id == id }
                        if (art != null && isNativeAndroidProject(art.files)) autoBuildApk(art)
                    }
                }

                is PipelineEvent.Failed -> {
                    updateMessage(convId, msgId) { it.copy(error = ev.message) }
                }
            }
        }
    }

    // ---------- artifacts ----------

    fun openArtifact(id: String) { _activeArtifactId.value = id }
    fun closeArtifact() { _activeArtifactId.value = null }

    val activeArtifact: Artifact?
        get() = _artifacts.value.firstOrNull { it.id == _activeArtifactId.value }

    fun deleteArtifact(id: String) {
        _artifacts.value = _artifacts.value.filterNot { it.id == id }
        if (_activeArtifactId.value == id) _activeArtifactId.value = null
        persistArtifacts()
    }

    fun togglePin(id: String) {
        _artifacts.value = _artifacts.value.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        persistArtifacts()
    }

    /**
     * One cheap background call to give the project a real name instead of the raw prompt. Runs
     * detached so it never delays the build, and silently keeps the local [cleanProjectName]
     * fallback if it fails — a naming call is not worth surfacing an error over.
     */
    private fun autoNameProject(artifactId: String, convId: String, prompt: String) {
        viewModelScope.launch {
            val name = try {
                LlmClient.complete(
                    _settings.value.provider(RoleKey.FAST), "Namer",
                    listOf(ChatTurn("user", PROJECT_NAME_PROMPT.format(prompt.take(300)))),
                    // Not 16 — a reasoning model burns the whole budget thinking and returns its
                    // scratchpad instead of a name (verified live against NIM).
                    temperature = 0.3, maxTokens = 512,
                    // A chatty model may explain itself first; the name is the last real line.
                ).trim().lines().last { it.isNotBlank() }.trim().trim('"', '\'', '.', '`', '*')
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            // Guard against a model that ignores the format and returns a sentence.
            if (name.isBlank() || name.length > 40 || name.split(" ").size > 5) return@launch
            renameArtifact(artifactId, name)
            renameConversation(convId, name)
            persistConversations()
        }
    }

    fun renameArtifact(id: String, title: String) {
        if (title.isBlank()) return
        _artifacts.value = _artifacts.value.map { if (it.id == id) it.copy(title = title.trim()) else it }
        persistArtifacts()
    }

    /** Clones a project as a brand-new, independent one — safe to experiment on without touching the original. */
    fun duplicateArtifact(id: String): String? {
        val source = _artifacts.value.firstOrNull { it.id == id } ?: return null
        val newId = UUID.randomUUID().toString()
        val copy = source.copy(
            id = newId,
            title = "${source.title} (copy)",
            createdAt = System.currentTimeMillis(),
            pinned = false,
            buildAttempts = emptyList(),
        )
        _artifacts.value = _artifacts.value + copy
        persistArtifacts()
        return newId
    }

    private fun mutateFiles(artifactId: String, block: (List<GeneratedFile>) -> List<GeneratedFile>) {
        _artifacts.value = _artifacts.value.map {
            if (it.id == artifactId) it.copy(files = block(it.files)) else it
        }
        persistArtifacts()
        // Every real edit path (save, rename, delete, add, regenerate) routes through here, so an
        // edited website redeploys itself the same way a freshly built one does.
        autoDeployAndAnnounce(artifactId)
    }

    fun updateFileContent(artifactId: String, path: String, content: String) =
        mutateFiles(artifactId) { files -> files.map { if (it.path == path) it.copy(content = content) else it } }

    fun renameFile(artifactId: String, oldPath: String, newPath: String) {
        if (newPath.isBlank() || newPath == oldPath) return
        mutateFiles(artifactId) { files ->
            if (files.any { it.path == newPath }) files
            else files.map { if (it.path == oldPath) it.copy(path = newPath.trim()) else it }
        }
    }

    fun deleteFile(artifactId: String, path: String) =
        mutateFiles(artifactId) { files -> files.filterNot { it.path == path } }

    fun addFile(artifactId: String, path: String, content: String = "") {
        if (path.isBlank()) return
        mutateFiles(artifactId) { files ->
            if (files.any { it.path == path }) files else files + GeneratedFile(path.trim(), content)
        }
    }

    /** Non-null path currently being regenerated, so the UI can show a spinner on just that chip. */
    private val _regeneratingFile = MutableStateFlow<String?>(null)
    val regeneratingFile: StateFlow<String?> = _regeneratingFile.asStateFlow()

    fun regenerateFile(artifact: Artifact, path: String, instruction: String) {
        if (_regeneratingFile.value != null) return
        val target = artifact.files.firstOrNull { it.path == path } ?: return
        _regeneratingFile.value = path
        viewModelScope.launch {
            try {
                val rewritten = LlmClient.complete(
                    _settings.value.provider(RoleKey.KIMI), "Kimi K3",
                    listOf(
                        ChatTurn(
                            "system",
                            "You are Kimi K3. Rewrite exactly one file from a project. Output ONLY that file " +
                                "as a single ### FILE: block in the usual format, nothing else — no other files, " +
                                "no prose.",
                        ),
                        ChatTurn(
                            "user",
                            "Project context (for reference, do not rewrite these):\n" +
                                artifact.files.filterNot { it.path == path }
                                    .joinToString("\n\n") { "### FILE: ${it.path}\n```\n${it.content.take(1500)}\n```" } +
                                "\n\nFile to rewrite: ${target.path}\nCurrent content:\n```\n${target.content}\n```\n\n" +
                                "Instruction: ${instruction.ifBlank { "Improve it — fix any bugs, tidy it up." }}",
                        ),
                    ),
                    maxTokens = 8192,
                )
                val fixed = parseFileBlocks(rewritten).firstOrNull()
                if (fixed != null) updateFileContent(artifact.id, path, fixed.content)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort — the UI just stops showing the spinner; the file is left as-is.
            } finally {
                _regeneratingFile.value = null
            }
        }
    }

    // ---------- terminal agent ----------

    fun connectTerminal() {
        val s = _settings.value
        TerminalClient.connect(s.terminalUrl, s.terminalAuthToken)
    }

    /**
     * Connects on launch and keeps retrying with a backoff, because the usual reason this fails
     * is a tunnel that simply hasn't been started yet — worth picking up on its own once it is.
     */
    private suspend fun connectRetryLoop(requireAutoConnectSetting: Boolean) {
        try {
            var delayMs = 4_000L
            repeat(20) {
                if (TerminalClient.connected.value) return
                val s = _settings.value
                if (s.terminalUrl.isBlank()) return
                if (requireAutoConnectSetting && !s.autoConnectTerminal) return
                TerminalClient.connect(s.terminalUrl, s.terminalAuthToken)
                delay(delayMs)
                if (TerminalClient.connected.value) return
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort background retry — a failure here must never take the app down.
        }
    }

    private fun startAutoConnect() {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = viewModelScope.launch { connectRetryLoop(requireAutoConnectSetting = true) }
    }

    /**
     * The user tapped Reconnect. This must always make a real attempt right now — not silently
     * no-op just because a background auto-connect loop happens to already be "active" (it could
     * be sitting in a 60s delay between retries), and not gated by the auto-connect setting,
     * which only controls whether connecting happens automatically at launch.
     */
    fun retryTerminal() {
        autoConnectJob?.cancel()
        autoConnectJob = viewModelScope.launch { connectRetryLoop(requireAutoConnectSetting = false) }
    }

    fun disconnectTerminal() = TerminalClient.disconnect()

    /**
     * Previously the agent just refused to run ("Connect it on the Terminal tab first") if the
     * socket wasn't already open — even though it already has everything it needs (URL, token) to
     * connect itself. Now it does: one real connection attempt, waiting for the handshake to
     * actually complete (or fail) rather than firing-and-hoping, since TerminalClient.connect()
     * only starts the async connect and returns immediately.
     */
    private suspend fun ensureTerminalConnected(timeoutMs: Long = 20_000): Boolean {
        if (TerminalClient.connected.value) return true
        val s = _settings.value
        if (s.terminalUrl.isBlank()) return false
        TerminalClient.connect(s.terminalUrl, s.terminalAuthToken)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (TerminalClient.connected.value) return true
            delay(300)
        }
        return TerminalClient.connected.value
    }

    fun runAgent(
        goal: String,
        files: List<GeneratedFile>,
        requiresConfirmation: Boolean = false,
        artifactId: String? = null,
        envVars: Map<String, String> = emptyMap(),
    ) {
        if (_agentRunning.value) return
        _agentRunning.value = true
        syncBusy("Building APK...")
        _agentLog.value = ""
        var succeeded = false
        var lastLine = ""
        viewModelScope.launch {
            try {
                if (requiresConfirmation) {
                    // Stated once, not asked. Tapping "Build APK" already *is* the decision to
                    // compile — putting a second yes/no in front of it added a step without adding
                    // a choice, and the Stop button is a real way out at any point after this.
                    appendAgentLog("Building on your connected machine — real commands run there. Stop anytime.")
                }
                if (!TerminalClient.connected.value) {
                    appendAgentLog("\n• Connecting to your terminal...")
                    val connectedNow = ensureTerminalConnected()
                    if (!connectedNow) {
                        val why = TerminalClient.status.value
                        lastLine = "Couldn't connect to your terminal ($why). Check the URL on the Terminal tab."
                        appendAgentLog("\n\n[!] $lastLine")
                        return@launch
                    }
                    appendAgentLog(" connected.")
                }
                runTerminalAgent(
                    goal, _settings.value, files,
                    envVars = envVars,
                ).collect { ev ->
                    when (ev) {
                        is PipelineEvent.Step -> { appendAgentLog("\n• ${ev.text}"); lastLine = ev.text }
                        is PipelineEvent.Chunk -> appendAgentLog(ev.text)
                        is PipelineEvent.Failed -> { appendAgentLog("\n\n[!] ${ev.message}"); lastLine = ev.message }
                        is PipelineEvent.Done -> { appendAgentLog("\n\n[done]"); succeeded = true }
                        else -> Unit
                    }
                }
                // Only on the success path, and inside the try so a transfer failure is reported
                // through the same handler as any other build failure.
                if (succeeded) fetchBuiltApk(artifactId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastLine = e.message ?: "Unknown error"
                appendAgentLog("\n\n[!] $lastLine")
            } finally {
                _agentRunning.value = false
                syncBusy()
                artifactId?.let { id ->
                    val attempt = BuildAttempt(System.currentTimeMillis(), succeeded, lastLine.take(200))
                    _artifacts.value = _artifacts.value.map {
                        if (it.id == id) it.copy(buildAttempts = (it.buildAttempts + attempt).takeLast(20)) else it
                    }
                    persistArtifacts()
                }
            }
        }
    }

    /**
     * Brings the APK the agent just built on the user's machine back onto the phone and offers it
     * in chat as a real, tappable file.
     *
     * Without this the build "succeeded" and then went nowhere — the .apk sat on a remote machine
     * with only its path printed in a log, which is not a deliverable. The transfer runs through
     * the same terminal connection (see ApkFetch) and is checksum-verified, because a silently
     * corrupted APK that installs and then misbehaves is worse than an honest failure.
     *
     * Every outcome is reported into the conversation. A build that produced no .apk, a transfer
     * that failed, and a file that arrived intact are three different things the user needs to be
     * able to tell apart.
     */
    private suspend fun fetchBuiltApk(artifactId: String?) {
        val convId = artifactId?.let { findConversationIdForArtifact(it) } ?: _activeConversationId.value
        val title = artifactId?.let { id -> _artifacts.value.firstOrNull { it.id == id }?.title } ?: "app"

        fun say(text: String, file: MessageFile? = null) {
            val id = convId ?: return
            addMessage(id, Message(id = UUID.randomUUID().toString(), role = "assistant", content = text, attachment = file))
            persistConversations()
        }

        appendAgentLog("\n\n• Looking for the built APK...")
        val remote = ApkFetch.findApk("~/chomugiri-build")
        if (remote == null) {
            appendAgentLog(" none found.")
            say("The build finished but no .apk turned up on your machine, so there is nothing to hand back. The build log above has what actually happened.")
            return
        }

        appendAgentLog("\n• Pulling $remote back to your phone...")
        val result = ApkFetch.pull(remote) { got, total ->
            val pct = if (total > 0) (got * 100 / total).toInt() else 0
            BuildService.start(getApplication(), "Downloading APK... $pct%")
        }

        when (result) {
            is ApkFetch.Result.Failed -> {
                appendAgentLog("\n[!] ${result.reason}")
                say("The APK built fine, but bringing it back failed: ${result.reason}\n\nIt is still on your machine at $remote.")
            }
            is ApkFetch.Result.Ok -> {
                val saved = try {
                    saveApkToCache(title, result.bytes)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendAgentLog("\n[!] Couldn't save it: ${e.message}")
                    say("The APK came back intact but couldn't be saved on this phone: ${e.message ?: "storage error"}")
                    return
                }
                appendAgentLog("\n• Saved ${saved.prettySize()} to your phone.")
                say("Your APK is ready — ${saved.prettySize()}. Tap it to install, or use the share button to save it anywhere.", saved)
            }
        }
    }

    /**
     * Written into the cache dir's `share/` subfolder, which is the one path file_paths.xml
     * exposes through FileProvider — anywhere else and the install/share intent gets no permission
     * to read it back.
     */
    private fun saveApkToCache(title: String, bytes: ByteArray): MessageFile {
        val dir = java.io.File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        val safe = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "app" }
        val file = java.io.File(dir, "$safe-${System.currentTimeMillis()}.apk")
        file.writeBytes(bytes)
        return MessageFile(path = file.absolutePath, name = file.name, sizeBytes = file.length(), kind = "apk")
    }

    /**
     * Replaces the bare "N file(s) ready" line with a real description of what was built.
     *
     * A file count is a receipt, not an answer — it says nothing about what the thing does, and it
     * arrives in English regardless of the language the whole conversation happened in. The
     * summary is generated from the real file list, so it can only describe what was actually
     * written. It is a nicety, never a blocker: if the call fails, the count line already showing
     * stays exactly as it is.
     */
    private suspend fun summariseBuild(
        convId: String, msgId: String, prompt: String, files: List<GeneratedFile>,
    ) {
        val manifest = files.joinToString("\n") { f ->
            "- ${f.path} (${f.content.lines().size} lines)"
        }.take(4000)
        val raw = try {
            LlmClient.complete(
                _settings.value.provider(RoleKey.FAST), "Summary",
                listOf(ChatTurn("user", BUILD_SUMMARY_PROMPT.format(prompt.take(500), manifest))),
                // Budget covers the scratchpad as well as the answer: the FAST default is a
                // reasoning model, and starving it mid-thought was what truncated the summary
                // before the real answer ever appeared.
                temperature = 0.4, maxTokens = 1400,
            ).trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        // The FAST default leaks its chain-of-thought into content rather than the separate
        // reasoning_content field — measured, not assumed: asked for this summary in Hinglish it
        // returned 406 words of visible deliberation and never reached an answer. Telling it to
        // think freely and then mark the answer fixes that at the source (3/3 clean across
        // Hinglish and English once the marker was added); everything before the marker is
        // dropped here. A response with no marker at all is only trusted if it is already short.
        val summary = raw.substringAfterLast(SUMMARY_MARKER, if (raw.length <= 600) raw else "").trim()
        if (summary.isBlank() || summary.length > 900) return
        updateMessage(convId, msgId) { it.copy(content = summary) }
        persistConversations()
    }

    /**
     * Answers an APK request that cannot be started, in the user's own language.
     *
     * Written as a real model call rather than a fixed string because most of this app's use is
     * Hinglish, and a hardcoded English sentence is the wrong answer for most of the people who
     * will see it. If that one cheap call fails — which is entirely possible, since a dead network
     * is one reason to be here — a plain bilingual fallback still says the useful thing rather
     * than leaving the message empty.
     */
    private suspend fun explainTerminalOffline(convId: String, msgId: String, userText: String) {
        val fallback = "Terminal abhi connected nahi hai, aur APK tumhari apni machine par hi ban " +
            "sakta hai. ttyd start karke uska URL Settings > My Terminal me daal do, phir yehi " +
            "message dobara bhejo — main APK bana dunga."
        val reply = try {
            LlmClient.complete(
                _settings.value.provider(RoleKey.FAST), "Fast Chat",
                listOf(ChatTurn("user", TERMINAL_OFFLINE_PROMPT.format(userText.take(400)))),
                temperature = 0.4, maxTokens = 400,
            ).trim().ifBlank { fallback }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
        updateMessage(convId, msgId) { it.copy(streaming = false, content = reply, kind = "chat") }
        persistConversations()
    }

    /**
     * Compiles a just-generated Android project without waiting to be asked.
     *
     * Someone who says "make me an app" wants the app, not its source. Leaving the APK behind a
     * separate tap in another screen meant the pipeline finished by handing over Kotlin files and
     * calling that done. Guarded so it can only fire when it can actually succeed, and so it can
     * never stack on top of a build already running.
     */
    private fun autoBuildApk(artifact: Artifact) {
        if (_agentRunning.value) return
        if (!canBuildApk()) return
        buildApkFromArtifact(artifact)
    }

    /**
     * Both conditions together, because either one alone blocks a compile just as completely.
     * Checked before the pipeline starts and again before the build fires, so the two decisions
     * can never disagree and leave a run that finishes with nothing to show for it.
     */
    private fun canBuildApk(): Boolean =
        TerminalClient.connected.value && _settings.value.agentTerminalEnabled

    fun buildApkFromArtifact(artifact: Artifact) = runAgent(
        apkBuildGoal(artifact.title, artifact.files), artifact.files,
        requiresConfirmation = true, artifactId = artifact.id, envVars = artifact.envVars,
    )

    fun updateEnvVars(artifactId: String, vars: Map<String, String>) {
        _artifacts.value = _artifacts.value.map { if (it.id == artifactId) it.copy(envVars = vars) else it }
        persistArtifacts()
    }

    // ---------- deploy ----------

    fun deployArtifact(artifact: Artifact) {
        val current = _deployState.value[artifact.id]
        if (current is DeployUiState.Deploying) return
        _deployState.value = _deployState.value + (artifact.id to DeployUiState.Deploying)
        viewModelScope.launch {
            val result = try {
                val r = VercelClient.deploy(_settings.value.vercelToken, artifact.title, artifact.files)
                DeployUiState.Success(r.url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DeployUiState.Failed(e.message ?: "Deploy failed.")
            }
            _deployState.value = _deployState.value + (artifact.id to result)
        }
    }

    private fun findConversationIdForArtifact(artifactId: String): String? =
        _conversations.value.firstOrNull { conv -> conv.messages.any { it.artifactId == artifactId } }?.id

    /**
     * Fires automatically after a website is built or its files are edited — no manual Deploy tap
     * needed. Only real, honest conditions gate it: a Vercel token must actually be set, the
     * artifact must actually contain an .html file (this API ships static files with no build
     * step, so anything else wouldn't work), and it must not be a binary artifact like a .pptx
     * (VercelClient sends file content as raw text, which would corrupt base64 bytes). Posts the
     * real deploy result — success or failure — back into whichever conversation this artifact
     * belongs to, so the live link genuinely shows up in chat rather than only in the panel.
     */
    private fun autoDeployAndAnnounce(artifactId: String) {
        val settings = _settings.value
        if (settings.vercelToken.isBlank()) return
        val artifact = _artifacts.value.firstOrNull { it.id == artifactId } ?: return
        if (artifact.files.none { it.path.endsWith(".html", ignoreCase = true) }) return
        if (artifact.files.any { it.encoding == "base64" }) return
        if (_deployState.value[artifactId] is DeployUiState.Deploying) return

        _deployState.value = _deployState.value + (artifactId to DeployUiState.Deploying)
        viewModelScope.launch {
            val result = try {
                val r = VercelClient.deploy(settings.vercelToken, artifact.title, artifact.files)
                DeployUiState.Success(r.url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DeployUiState.Failed(e.message ?: "Deploy failed.")
            }
            _deployState.value = _deployState.value + (artifactId to result)

            val convId = findConversationIdForArtifact(artifactId) ?: return@launch
            val text = when (result) {
                is DeployUiState.Success ->
                    if (result.url != null) "Deployed to Vercel: ${result.url}"
                    else "Deployed to Vercel, but it didn't return a URL this time — check your Vercel dashboard."
                is DeployUiState.Failed -> "Auto-deploy to Vercel failed: ${result.message}"
                is DeployUiState.Deploying -> return@launch
            }
            addMessage(convId, Message(id = UUID.randomUUID().toString(), role = "assistant", content = text))
            persistConversations()
        }
    }

    // ---------- agent permission gate ----------

    /**
     * Suspends until the user taps Allow or Deny in the confirmation dialog.
     *
     * Two failure modes this has to survive, both reachable in normal use:
     *
     * - The waiting coroutine is cancelled while the dialog is up (Stop tapped during the "Build
     *   this?" plan gate, or the chat deleted mid-build). Without invokeOnCancellation the request
     *   stayed in _pendingPermission with nobody left listening, leaving a dead dialog on screen.
     * - A second conversation reaches a gate while the first one's dialog is still open — this app
     *   deliberately allows concurrent runs. Overwriting the StateFlow used to strand the first
     *   coroutine with no dialog and no answer, suspended forever. It is now denied explicitly so
     *   it fails fast and visibly instead of hanging.
     */
    private suspend fun askPermission(kind: String, description: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            _pendingPermission.value?.respond?.invoke(false)
            lateinit var request: AgentPermissionRequest
            request = AgentPermissionRequest(kind, description) { allowed ->
                // Only ever clear our own request, never one that has since replaced it.
                if (_pendingPermission.value === request) _pendingPermission.value = null
                if (cont.isActive) cont.resumeWith(Result.success(allowed))
            }
            _pendingPermission.value = request
            cont.invokeOnCancellation {
                if (_pendingPermission.value === request) _pendingPermission.value = null
            }
        }
}

/** How often a streaming response is allowed to push a UI update. See runFastChat. */
private const val STREAM_UI_INTERVAL_MS = 50L

/** Upper bound on retained reasoning steps per message — see the Step handler in consume(). */
private const val MAX_STEPS_PER_MESSAGE = 120

sealed class DeployUiState {
    data object Deploying : DeployUiState()
    data class Success(val url: String?) : DeployUiState()
    data class Failed(val message: String) : DeployUiState()
}

data class AgentPermissionRequest(
    val kind: String,
    val description: String,
    val respond: (Boolean) -> Unit,
)
