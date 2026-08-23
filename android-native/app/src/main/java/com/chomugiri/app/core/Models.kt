package com.chomugiri.app.core

import kotlinx.serialization.Serializable

enum class RoleKey { FAST, KIMI, GLM, DEEPSEEK, NEMOTRON }

val ROLE_ORDER = listOf(RoleKey.FAST, RoleKey.KIMI, RoleKey.GLM, RoleKey.DEEPSEEK, RoleKey.NEMOTRON)

val ROLE_LABELS = mapOf(
    RoleKey.FAST to "Fast Chat Model",
    RoleKey.KIMI to "Kimi K3 (Coder)",
    RoleKey.GLM to "GLM 5.3 (Auditor)",
    RoleKey.DEEPSEEK to "DeepSeek R1 (Deep Logic)",
    RoleKey.NEMOTRON to "Nemotron 3 Ultra 550B (Safety Net)",
)

@Serializable
data class ProviderConfig(
    val apiKey: String = "",
    val baseUrl: String = NIM_BASE_URL,
    val model: String = "",
)

/**
 * A model the user has wired up themselves, beyond the five fixed pipeline roles — their own
 * name, their own OpenAI-compatible endpoint, their own key. Selectable from chat the same way a
 * pipeline role is, via the per-message model picker.
 */
@Serializable
data class CustomModel(
    val id: String,
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
)

const val NIM_BASE_URL = "https://integrate.api.nvidia.com/v1"
const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

/**
 * Gemini speaks OpenAI's chat/completions shape at this path, so it works as an ordinary chat or
 * coding provider through the existing client with no special-casing — verified live: the
 * endpoint answers "Please pass a valid API key" rather than 404.
 */
const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"

/**
 * Every field here is user-supplied. No key of any kind is compiled into the app — the defaults
 * below carry base URLs and model ids only.
 */
@Serializable
data class AppSettings(
    val providers: Map<String, ProviderConfig> = defaultProviders(),
    val maxAuditLoops: Int = 2,
    /**
     * ttyd behind a tunnel — the user's own, entered by them in Settings. Never pre-filled: a
     * value baked into a shared build would hand every installer a shell on that one machine.
     */
    val terminalUrl: String = "",
    val terminalAuthToken: String = "",
    /** Lets the AI agent run commands on that terminal, not just the user. Off until set up. */
    val agentTerminalEnabled: Boolean = false,
    /** Once a URL is set, connect on launch and keep retrying instead of requiring a tap. */
    val autoConnectTerminal: Boolean = true,
    /** Web search for Deep Research. DuckDuckGo needs no key — see SearchClient. */
    val searchProvider: String = "duckduckgo",
    val searchApiKey: String = "",
    /** Used only when the user taps Deploy on a project. Never bundled with the app. */
    val vercelToken: String = "",
    /** "dark" or "light" — user-controlled in Settings, dark by default. */
    val themeMode: String = "dark",
    /** User-saved terminal commands, shown as tap-to-run chips on the Terminal tab. */
    val terminalMacros: List<String> = emptyList(),
    /**
     * Which provider fills {{IMAGE: ...}} markers. NIM is the default because the same nvapi- key
     * that already drives the chat roles also reaches NVIDIA's image models, so there is nothing
     * extra to sign up for — and FLUX.1-dev there is genuinely strong. Gemini stays available for
     * anyone who prefers it.
     */
    val imageProvider: String = "nim",
    val nimImageApiKey: String = "",
    val nimImageModel: String = com.chomugiri.app.net.NIM_DEFAULT_IMAGE_MODEL,
    /** Gemini's key — usable for images, and also as a normal chat/coding provider (see GEMINI_BASE_URL). */
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash-image",
    /** Real video generation via Hugging Face's Inference Providers router. Never bundled — a
     * free HF account's own token, with a small monthly credit (not unlimited). */
    val huggingfaceToken: String = "",
    val huggingfaceVideoModel: String = com.chomugiri.app.net.HF_DEFAULT_VIDEO_MODEL_PATH,
    /**
     * Run every role on one model instead of five. Five separate roles is the right shape when
     * you have broad access, but it is a real barrier when you only have one model available —
     * so this collapses them onto the coder's config, which is the one that has to be capable
     * enough for the app's actual job. A model that can code can also chat; the reverse is not
     * reliably true, which is why the coder is the one kept.
     */
    val singleModelMode: Boolean = false,
    /** User-defined models with their own endpoint, alongside the five fixed pipeline roles. */
    val customModels: List<CustomModel> = emptyList(),
) {
    fun provider(role: RoleKey): ProviderConfig {
        val effective = if (singleModelMode) RoleKey.KIMI else role
        return providers[effective.name] ?: ProviderConfig(model = defaultModelFor(effective))
    }

    fun withProvider(role: RoleKey, cfg: ProviderConfig): AppSettings =
        copy(providers = providers.toMutableMap().apply { put(role.name, cfg) })
}

fun defaultModelFor(role: RoleKey): String = when (role) {
    // Was llama-3.1-8b — a 2024-era 8B. nemotron-3-nano is a far newer MoE that only activates
    // ~3B per token, so routing/chat stays fast while being much less prone to misjudging things.
    RoleKey.FAST -> "nvidia/nemotron-3-nano-30b-a3b"
    // The single biggest quality bug in this app: the role is *named* Kimi K3 and prompted as
    // Kimi K3, but pointed at meta/llama-3.1-70b — a 2024 general model that writes plausible but
    // visually generic front-end code. moonshotai/kimi-k3 is genuinely available on NIM (verified
    // against its live /models catalog), so the coder is now actually the model it claims to be.
    RoleKey.KIMI -> "moonshotai/kimi-k3"
    // glm-5.2 hit end-of-life 2026-08-21 (confirmed via a live HTTP 410 "Gone" from the provider)
    // and glm-5.3 is the current live successor — verified against the provider's own /models
    // catalog before picking it, not guessed.
    RoleKey.GLM -> "z-ai/glm-5.3"
    RoleKey.DEEPSEEK -> "deepseek-ai/deepseek-v4-flash-0731"
    RoleKey.NEMOTRON -> "nvidia/nemotron-3-ultra-550b-a55b"
}

fun defaultProviders(): Map<String, ProviderConfig> =
    ROLE_ORDER.associate { it.name to ProviderConfig(model = defaultModelFor(it)) }

/**
 * Quick-select tiers for the one real dial the swarm has: how many GLM audit rounds run, and
 * whether DeepSeek/Nemotron get invoked at all. Same models throughout every tier — nothing
 * about "AI power" actually changes, only how many passes it makes over the same code.
 */
data class PowerTier(val label: String, val auditLoops: Int, val description: String)

val POWER_TIERS = listOf(
    PowerTier("LITE", 0, "Kimi writes the code once. No audit, no fallback, no safety check — fastest, for a quick throwaway script."),
    PowerTier("ECONOMY", 1, "Kimi writes, GLM audits once and Kimi fixes what it finds, then Nemotron does a final safety pass."),
    PowerTier("POWER", 2, "The default: 2 audit rounds between Kimi and GLM before Nemotron's safety pass. Good balance of speed and quality."),
    PowerTier("EXTRA", 3, "3 audit rounds — more chances for GLM to catch something Kimi missed, at the cost of more time."),
    PowerTier("MAX", 4, "4 audit rounds. Meaningfully slower; worth it for something you actually want to ship as-is."),
    PowerTier("ULTRAMAX", 5, "5 audit rounds — thorough, and a genuinely long wait since every round is a real model call."),
    PowerTier("GOJO", 6, "6 audit rounds. At this depth you're mostly paying for diminishing returns, but it's here if you want it."),
    PowerTier("SUKUNA", 7, "7 audit rounds — the deepest this app goes. Same models as every other tier, just the most passes over the same code."),
)

@Serializable
data class ChatTurn(val role: String, val content: String)

@Serializable
data class GeneratedFile(
    val path: String,
    val content: String,
    /** "text" (default, UTF-8) or "base64" — a real binary file like a .pptx stores its bytes as
     * base64 in [content] since a project's files are otherwise plain UTF-8 text. */
    val encoding: String = "text",
)

/** The file's real bytes — base64-decoded for a binary file, UTF-8 otherwise. Always what should
 * actually be written to disk; never assume [GeneratedFile.content] is safe to write as text. */
fun GeneratedFile.rawBytes(): ByteArray =
    if (encoding == "base64") android.util.Base64.decode(content, android.util.Base64.NO_WRAP)
    else content.toByteArray(Charsets.UTF_8)

@Serializable
data class BuildAttempt(
    val timestamp: Long,
    val success: Boolean,
    val summary: String,
)

@Serializable
data class Artifact(
    val id: String,
    val title: String,
    val files: List<GeneratedFile>,
    val createdAt: Long,
    val pinned: Boolean = false,
    /** Wall-clock time the pipeline took to produce this, in ms — null for hand-added projects. */
    val buildMs: Long? = null,
    val auditRounds: Int? = null,
    /** Which swarm stage's output actually shipped — "Kimi K3" / "...+ GLM audit" / "DeepSeek R1 fallback" / etc. */
    val resolvedBy: String? = null,
    /** GLM's real findings from the last audit round that found something, kept after the chat log scrolls away. */
    val lastAuditIssues: List<String> = emptyList(),
    /** User-supplied, per-project env vars — exported into the shell before any terminal build command runs. */
    val envVars: Map<String, String> = emptyMap(),
    val buildAttempts: List<BuildAttempt> = emptyList(),
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
    /** Which model actually produced this reply — "Fast Chat", a forced role's label, or "Deep Research". */
    val modelUsed: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Renders a conversation as plain markdown, for sharing/exporting outside the app. */
fun conversationToMarkdown(conversation: Conversation): String = buildString {
    appendLine("# ${conversation.title}")
    appendLine()
    conversation.messages.forEach { m ->
        appendLine(if (m.role == "user") "**You:**" else "**ChomuGiri:**")
        appendLine()
        appendLine(m.content.ifBlank { "_(no text — see the app for any generated files)_" })
        appendLine()
    }
}

/** Progress emitted by the swarm pipeline as it runs. */
sealed class PipelineEvent {
    data class Step(val text: String, val done: Boolean = false) : PipelineEvent()
    data class Files(val files: List<GeneratedFile>) : PipelineEvent()
    data class Chunk(val text: String) : PipelineEvent()
    data class Failed(val message: String) : PipelineEvent()
    data class Done(
        val files: List<GeneratedFile>,
        /** Which stage's output shipped — empty when not applicable (research, terminal agent). */
        val resolvedBy: String = "",
        val auditIssues: List<String> = emptyList(),
    ) : PipelineEvent()
}
