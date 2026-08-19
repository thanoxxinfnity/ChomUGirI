package com.chomugiri.app.core

import kotlinx.serialization.Serializable

enum class RoleKey { FAST, KIMI, GLM, DEEPSEEK, NEMOTRON }

val ROLE_ORDER = listOf(RoleKey.FAST, RoleKey.KIMI, RoleKey.GLM, RoleKey.DEEPSEEK, RoleKey.NEMOTRON)

val ROLE_LABELS = mapOf(
    RoleKey.FAST to "Fast Chat Model",
    RoleKey.KIMI to "Kimi K3 (Coder)",
    RoleKey.GLM to "GLM 5.2 (Auditor)",
    RoleKey.DEEPSEEK to "DeepSeek R1 (Deep Logic)",
    RoleKey.NEMOTRON to "Nemotron 3 Ultra 550B (Safety Net)",
)

@Serializable
data class ProviderConfig(
    val apiKey: String = "",
    val baseUrl: String = NIM_BASE_URL,
    val model: String = "",
)

const val NIM_BASE_URL = "https://integrate.api.nvidia.com/v1"
const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

/**
 * Every field here is user-supplied. No key of any kind is compiled into the app — the defaults
 * below carry base URLs and model ids only.
 */
@Serializable
data class AppSettings(
    val providers: Map<String, ProviderConfig> = defaultProviders(),
    val maxAuditLoops: Int = 2,
    /** A terminal the user hosts and exposes themselves (ttyd behind their own tunnel). */
    val terminalUrl: String = "",
    val terminalAuthToken: String = "",
    /** Lets the AI agent run commands on that terminal, not just the user. */
    val agentTerminalEnabled: Boolean = false,
    /** Web search for Deep Research. Without this, Deep Research stays off — see SearchClient. */
    val searchProvider: String = "tavily",
    val searchApiKey: String = "",
) {
    fun provider(role: RoleKey): ProviderConfig =
        providers[role.name] ?: ProviderConfig(model = defaultModelFor(role))

    fun withProvider(role: RoleKey, cfg: ProviderConfig): AppSettings =
        copy(providers = providers.toMutableMap().apply { put(role.name, cfg) })
}

fun defaultModelFor(role: RoleKey): String = when (role) {
    RoleKey.FAST -> "meta/llama-3.1-8b-instruct"
    RoleKey.KIMI -> "meta/llama-3.1-70b-instruct"
    RoleKey.GLM -> "z-ai/glm-5.2"
    RoleKey.DEEPSEEK -> "deepseek-ai/deepseek-v4-flash-0731"
    RoleKey.NEMOTRON -> "nvidia/nemotron-3-ultra-550b-a55b"
}

fun defaultProviders(): Map<String, ProviderConfig> =
    ROLE_ORDER.associate { it.name to ProviderConfig(model = defaultModelFor(it)) }

@Serializable
data class ChatTurn(val role: String, val content: String)

@Serializable
data class GeneratedFile(val path: String, val content: String)

@Serializable
data class Artifact(
    val id: String,
    val title: String,
    val files: List<GeneratedFile>,
    val createdAt: Long,
)

@Serializable
data class ThinkingStep(val text: String, val done: Boolean = false)

@Serializable
data class Message(
    val id: String,
    val role: String,           // "user" | "assistant"
    val content: String = "",
    val kind: String = "chat",  // "chat" | "pipeline" | "research"
    val artifactId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: List<ThinkingStep> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Progress emitted by the swarm pipeline as it runs. */
sealed class PipelineEvent {
    data class Step(val text: String, val done: Boolean = false) : PipelineEvent()
    data class Files(val files: List<GeneratedFile>) : PipelineEvent()
    data class Chunk(val text: String) : PipelineEvent()
    data class Failed(val message: String) : PipelineEvent()
    data class Done(val files: List<GeneratedFile>) : PipelineEvent()
}
