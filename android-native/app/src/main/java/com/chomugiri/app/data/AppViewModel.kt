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

    // ---------- the one entry point the UI calls ----------

    fun send(text: String, forcedIntent: Intent? = null) {
        if (text.isBlank()) return
        // Only refuse if THIS conversation already has a job running — a different, idle
        // conversation must stay free to send while another one is mid-pipeline.
        _activeConversationId.value?.let { if (jobs.containsKey(it)) return }
        val convId = ensureConversation(text)
        val s = _settings.value

        addMessage(convId, Message(id = UUID.randomUUID().toString(), role = "user", content = text))

        val assistantId = UUID.randomUUID().toString()
        val intent = forcedIntent ?: classifyIntent(text)
        addMessage(
            convId,
            Message(
                id = assistantId,
                role = "assistant",
                kind = when (intent) {
                    Intent.CHAT -> "chat"
                    Intent.PIPELINE -> "pipeline"
                    Intent.RESEARCH -> "research"
                },
                streaming = true,
            ),
        )

        val job = viewModelScope.launch {
            try {
                when (intent) {
                    Intent.CHAT -> runFastChat(convId, assistantId, text, s)
                    Intent.PIPELINE -> consume(convId, assistantId, runPipeline(text, s), text)
                    Intent.RESEARCH -> consume(convId, assistantId, runDeepResearch(text, s), text)
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

    private suspend fun runFastChat(convId: String, msgId: String, text: String, s: AppSettings) {
        val history = (activeConversation?.messages ?: emptyList())
            .filter { it.kind == "chat" && it.content.isNotBlank() && it.id != msgId }
            .takeLast(10)
            .map { ChatTurn(it.role, it.content) }

        val turns = listOf(ChatTurn("system", FAST_CHAT_SYSTEM_PROMPT)) + history + ChatTurn("user", text)
        val sb = StringBuilder()
        LlmClient.stream(s.provider(RoleKey.FAST), "Fast chat", turns, temperature = 0.6, maxTokens = 2048)
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
                    artifactId = id
                    val title = prompt.trim().take(48).ifBlank { "Generated project" }
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
                            if (it.id == id) it.copy(buildMs = elapsed, auditRounds = _settings.value.maxAuditLoops) else it
                        }
                    }
                    persistArtifacts()
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

    fun renameArtifact(id: String, title: String) {
        if (title.isBlank()) return
        _artifacts.value = _artifacts.value.map { if (it.id == id) it.copy(title = title.trim()) else it }
        persistArtifacts()
    }

    private fun mutateFiles(artifactId: String, block: (List<GeneratedFile>) -> List<GeneratedFile>) {
        _artifacts.value = _artifacts.value.map {
            if (it.id == artifactId) it.copy(files = block(it.files)) else it
        }
        persistArtifacts()
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
    private fun startAutoConnect() {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = viewModelScope.launch {
            var delayMs = 4_000L
            repeat(20) {
                if (TerminalClient.connected.value) return@launch
                val s = _settings.value
                if (!s.autoConnectTerminal || s.terminalUrl.isBlank()) return@launch
                TerminalClient.connect(s.terminalUrl, s.terminalAuthToken)
                delay(delayMs)
                if (TerminalClient.connected.value) return@launch
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun retryTerminal() = startAutoConnect()

    fun disconnectTerminal() = TerminalClient.disconnect()

    fun runAgent(goal: String, files: List<GeneratedFile>, requiresConfirmation: Boolean = false) {
        if (_agentRunning.value) return
        _agentRunning.value = true
        _agentLog.value = ""
        viewModelScope.launch {
            try {
                if (requiresConfirmation) {
                    val ok = askPermission("compile", "Compile an APK on your connected machine? This runs real build commands there.")
                    if (!ok) {
                        _agentLog.value = "Cancelled — compile was not confirmed."
                        return@launch
                    }
                }
                runTerminalAgent(goal, _settings.value, files, onConfirmRead = { path ->
                    askPermission("read", "Let ChomuGiri read \"$path\" on your machine?")
                }).collect { ev ->
                    when (ev) {
                        is PipelineEvent.Step -> appendAgentLog("\n• ${ev.text}")
                        is PipelineEvent.Chunk -> appendAgentLog(ev.text)
                        is PipelineEvent.Failed -> appendAgentLog("\n\n[!] ${ev.message}")
                        is PipelineEvent.Done -> appendAgentLog("\n\n[done]")
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                appendAgentLog("\n\n[!] ${e.message}")
            } finally {
                _agentRunning.value = false
            }
        }
    }

    fun buildApkFromArtifact(artifact: Artifact) =
        runAgent(apkBuildGoal(artifact.title), artifact.files, requiresConfirmation = true)

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
