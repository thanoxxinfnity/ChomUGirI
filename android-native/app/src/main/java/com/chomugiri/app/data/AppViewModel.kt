package com.chomugiri.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chomugiri.app.core.*
import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.TerminalClient
import com.chomugiri.app.net.VercelClient
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

    /** Non-null while the terminal agent is waiting on an explicit read/compile confirmation. */
    private val _pendingPermission = MutableStateFlow<AgentPermissionRequest?>(null)
    val pendingPermission: StateFlow<AgentPermissionRequest?> = _pendingPermission.asStateFlow()

    private val _deployState = MutableStateFlow<Map<String, DeployUiState>>(emptyMap())
    val deployState: StateFlow<Map<String, DeployUiState>> = _deployState.asStateFlow()

    private var autoConnectJob: Job? = null
    private var loaded = false

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                _settings.value = s
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
                    _conversations.value = list
                    if (_activeConversationId.value == null) {
                        _activeConversationId.value = list.maxByOrNull { it.updatedAt }?.id
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

    // ---------- conversations ----------

    val activeConversation: Conversation?
        get() = _conversations.value.firstOrNull { it.id == _activeConversationId.value }

    fun newConversation() {
        _activeConversationId.value = null
    }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
    }

    fun deleteConversation(id: String) {
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
     */
    fun send(text: String, forcedIntent: Intent? = null, forcedRole: RoleKey? = null) {
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

        val job = viewModelScope.launch {
            try {
                // The router is a real AI decision (the Fast Chat role), not a keyword list — that
                // is what stops "what's the capital of France" from ever waking the coding swarm.
                val intent = if (forcedRole != null) Intent.CHAT else (forcedIntent ?: classifyIntentAi(text, s))
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
                    Intent.CHAT -> runFastChat(convId, assistantId, text, s, forcedRole ?: RoleKey.FAST)
                    Intent.PIPELINE -> {
                        // Real conversation history, not just this one isolated message — without
                        // it Kimi can't tell "make a website" -> its own clarifying question ->
                        // the user's answer is one continuous exchange, and would just ask again.
                        val history = (activeConversation?.messages ?: emptyList())
                            .filter { it.content.isNotBlank() && it.id != userMsgId && it.id != assistantId }
                            .takeLast(8)
                            .map { ChatTurn(it.role, it.content) }
                        consume(convId, assistantId, runPipeline(text, s, history), text)
                    }
                    Intent.RESEARCH -> consume(convId, assistantId, runDeepResearch(text, s), text)
                    Intent.PPTX -> consume(convId, assistantId, runPptxPipeline(text, s), text)
                    Intent.VIDEO -> consume(convId, assistantId, runVideoPipeline(text, s), text)
                }
            } catch (e: Exception) {
                updateMessage(convId, assistantId) {
                    it.copy(streaming = false, error = e.message ?: "Something went wrong.")
                }
            } finally {
                updateMessage(convId, assistantId) { it.copy(streaming = false) }
                jobs.remove(convId)
                _busyConversations.value = jobs.keys.toSet()
                persistConversations()
            }
        }
        jobs[convId] = job
        _busyConversations.value = jobs.keys.toSet()
    }

    /** Stops the given conversation's job, or the active one if none is given. */
    fun stop(convId: String? = null) {
        val id = convId ?: _activeConversationId.value ?: return
        jobs[id]?.cancel()
        jobs.remove(id)
        _busyConversations.value = jobs.keys.toSet()
    }

    private suspend fun runFastChat(convId: String, msgId: String, text: String, s: AppSettings, role: RoleKey = RoleKey.FAST) {
        val history = (activeConversation?.messages ?: emptyList())
            .filter { it.kind == "chat" && it.content.isNotBlank() && it.id != msgId }
            .takeLast(10)
            .map { ChatTurn(it.role, it.content) }

        val systemPrompt = if (role == RoleKey.FAST) FAST_CHAT_SYSTEM_PROMPT else
            "You are ${ROLE_LABELS[role]}, answering directly because the user picked you specifically for this message. Reply naturally and helpfully."
        val turns = listOf(ChatTurn("system", systemPrompt)) + history + ChatTurn("user", text)
        val sb = StringBuilder()
        LlmClient.stream(s.provider(role), ROLE_LABELS[role] ?: role.name, turns, temperature = 0.6, maxTokens = 2048)
            .collect { chunk ->
                sb.append(chunk)
                updateMessage(convId, msgId) { it.copy(content = sb.toString()) }
            }
    }

    private suspend fun consume(
        convId: String,
        msgId: String,
        flow: kotlinx.coroutines.flow.Flow<PipelineEvent>,
        prompt: String,
    ) {
        val steps = mutableListOf<ThinkingStep>()
        val body = StringBuilder()
        var artifactId: String? = null
        val startedAt = System.currentTimeMillis()

        flow.collect { ev ->
            when (ev) {
                is PipelineEvent.Step -> {
                    if (ev.done && steps.isNotEmpty() && !steps.last().done) {
                        steps[steps.lastIndex] = steps.last().copy(text = ev.text, done = true)
                    } else {
                        steps += ThinkingStep(ev.text, ev.done)
                    }
                    updateMessage(convId, msgId) { it.copy(steps = steps.toList()) }
                }

                is PipelineEvent.Chunk -> {
                    body.append(ev.text)
                    updateMessage(convId, msgId) { it.copy(content = body.toString()) }
                }

                is PipelineEvent.Files -> {
                    val id = artifactId ?: UUID.randomUUID().toString()
                    val isNew = artifactId == null
                    artifactId = id
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
                    _activeArtifactId.value = id
                    updateMessage(convId, msgId) { it.copy(artifactId = id) }
                    renameConversation(convId, title)
                }

                is PipelineEvent.Done -> {
                    if (body.isEmpty() && ev.files.isNotEmpty()) {
                        updateMessage(convId, msgId) {
                            it.copy(content = "Done — ${ev.files.size} file(s) ready. Open the project to view, export, or build it.")
                        }
                    }
                    artifactId?.let { id ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        _artifacts.value = _artifacts.value.map {
                            if (it.id == id) it.copy(
                                buildMs = elapsed,
                                auditRounds = _settings.value.maxAuditLoops,
                                resolvedBy = ev.resolvedBy.ifBlank { null } ?: it.resolvedBy,
                                lastAuditIssues = ev.auditIssues.ifEmpty { it.lastAuditIssues },
                            ) else it
                        }
                    }
                    persistArtifacts()
                    // A freshly built website deploys itself — no manual Deploy tap needed. Only
                    // does anything when a Vercel token is set and the project is a real website.
                    artifactId?.let { autoDeployAndAnnounce(it) }
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
                    temperature = 0.3, maxTokens = 16,
                ).trim().trim('"', '\'', '.', '`').lines().first().trim()
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

    fun runAgent(
        goal: String,
        files: List<GeneratedFile>,
        requiresConfirmation: Boolean = false,
        artifactId: String? = null,
        envVars: Map<String, String> = emptyMap(),
    ) {
        if (_agentRunning.value) return
        _agentRunning.value = true
        _agentLog.value = ""
        var succeeded = false
        var lastLine = ""
        viewModelScope.launch {
            try {
                if (requiresConfirmation) {
                    val ok = askPermission("compile", "Compile an APK on your connected machine? This runs real build commands there.")
                    if (!ok) {
                        _agentLog.value = "Cancelled — compile was not confirmed."
                        return@launch
                    }
                }
                runTerminalAgent(
                    goal, _settings.value, files,
                    onConfirmRead = { path -> askPermission("read", "Let ChomuGiri read \"$path\" on your machine?") },
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
            } catch (e: Exception) {
                lastLine = e.message ?: "Unknown error"
                appendAgentLog("\n\n[!] $lastLine")
            } finally {
                _agentRunning.value = false
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

    fun buildApkFromArtifact(artifact: Artifact) = runAgent(
        apkBuildGoal(artifact.title), artifact.files,
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

    /** Suspends until the user taps Allow or Deny in the confirmation dialog. */
    private suspend fun askPermission(kind: String, description: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            _pendingPermission.value = AgentPermissionRequest(kind, description) { allowed ->
                _pendingPermission.value = null
                if (cont.isActive) cont.resumeWith(Result.success(allowed))
            }
        }
}

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
