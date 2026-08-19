package com.chomugiri.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chomugiri.app.core.*
import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.TerminalClient
import kotlinx.coroutines.Job
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Live output of the terminal build agent. */
    private val _agentLog = MutableStateFlow("")
    val agentLog: StateFlow<String> = _agentLog.asStateFlow()

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    private var currentJob: Job? = null
    private var loaded = false

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                _settings.value = s
                if (!loaded) loaded = true
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

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        val convId = ensureConversation(text)
        val s = _settings.value

        addMessage(convId, Message(id = UUID.randomUUID().toString(), role = "user", content = text))

        val assistantId = UUID.randomUUID().toString()
        val intent = classifyIntent(text)
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

        _busy.value = true
        currentJob = viewModelScope.launch {
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
                _busy.value = false
                persistConversations()
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        _busy.value = false
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
                    val art = Artifact(id, title, ev.files, System.currentTimeMillis())
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

    // ---------- terminal agent ----------

    fun connectTerminal() {
        val s = _settings.value
        TerminalClient.connect(s.terminalUrl, s.terminalAuthToken)
    }

    fun disconnectTerminal() = TerminalClient.disconnect()

    fun runAgent(goal: String, files: List<GeneratedFile>) {
        if (_agentRunning.value) return
        _agentRunning.value = true
        _agentLog.value = ""
        viewModelScope.launch {
            try {
                runTerminalAgent(goal, _settings.value, files).collect { ev ->
                    when (ev) {
                        is PipelineEvent.Step -> _agentLog.value += "\n• ${ev.text}"
                        is PipelineEvent.Chunk -> _agentLog.value += ev.text
                        is PipelineEvent.Failed -> _agentLog.value += "\n\n[!] ${ev.message}"
                        is PipelineEvent.Done -> _agentLog.value += "\n\n[done]"
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                _agentLog.value += "\n\n[!] ${e.message}"
            } finally {
                _agentRunning.value = false
            }
        }
    }

    fun buildApkFromArtifact(artifact: Artifact) =
        runAgent(apkBuildGoal(artifact.title), artifact.files)
}
