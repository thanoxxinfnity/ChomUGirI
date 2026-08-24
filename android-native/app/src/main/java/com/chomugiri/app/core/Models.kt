package com.chomugiri.app.core

import kotlinx.serialization.Serializable

enum class RoleKey { FAST, KIMI, GLM, DEEPSEEK, NEMOTRON }

val ROLE_ORDER = listOf(RoleKey.FAST, RoleKey.KIMI, RoleKey.GLM, RoleKey.DEEPSEEK, RoleKey.NEMOTRON)

val ROLE_LABELS = mapOf(
    RoleKey.FAST to "Fast Chat Model",
    RoleKey.KIMI to "Kimi K3 (Coder)",
    RoleKey.GLM to "Step 3.7 Flash (Auditor)",
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
 * Hugging Face's Inference Providers router — OpenAI-compatible, and a third home for the coder
 * when NIM is having one of its routing wobbles. Verified against the live catalogue: it serves
 * moonshotai/Kimi-K3 across five providers (together, fireworks, featherless, baseten, deepinfra),
 * all reporting live. It is billed against a free account's monthly credit rather than being
 * unlimited, so it is offered as an alternative, not as "free forever".
 */
const val HF_ROUTER_BASE_URL = "https://router.huggingface.co/v1"

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
    /**
     * Legacy: the old audit-count dial. Kept only so an existing install can be migrated onto a
     * BuildMode on first load; nothing reads it to drive a build any more.
     */
    val maxAuditLoops: Int = 2,
    /** Which BuildMode drives a build. Empty means "not migrated yet" (see migrated()). */
    val buildMode: String = "",
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
    /**
     * A second home for the coder, tried automatically when the first one fails.
     *
     * The coder is the one role a build cannot proceed without, and NIM's routing has been
     * observed answering 200/404/429 for the same model seconds apart. One flaky minute killing a
     * whole run is the single most common way this app wastes someone's time, and a backup on
     * genuinely different infrastructure is the only real fix. Blank means no fallback configured.
     */
    val coderFallback: ProviderConfig = ProviderConfig(baseUrl = "", model = ""),
) {
    fun provider(role: RoleKey): ProviderConfig {
        val effective = if (singleModelMode) RoleKey.KIMI else role
        return providers[effective.name] ?: ProviderConfig(model = defaultModelFor(effective))
    }

    fun withProvider(role: RoleKey, cfg: ProviderConfig): AppSettings =
        copy(providers = providers.toMutableMap().apply { put(role.name, cfg) })

    /** The configured coder backup, or null when there isn't a usable one. */
    fun coderFallbackOrNull(): ProviderConfig? = coderFallback
        .takeIf { it.model.isNotBlank() && it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() }

    /** The active mode, falling back to the old audit count for anyone not yet migrated. */
    fun mode(): BuildMode =
        BUILD_MODES.firstOrNull { it.label == buildMode } ?: modeForAuditLoops(maxAuditLoops)
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
    // GLM used to live here and no longer does: NIM answers HTTP 410 "end of life 2026-08-21" for
    // z-ai/glm-5.2, and glm-5.3 was never a NIM model at all (a bad guess at a successor, which is
    // what produced the 404 on every build). GLM itself is fine — it is on OpenRouter, where
    // glm-5.2:free costs $0 — but that is a different endpoint and key, so it cannot be the
    // default for a NIM-keyed app. Settings > Auditor has one-tap GLM presets for anyone who
    // wants it. This default has to work with the key the user already has.
    // step-3.7-flash was picked by actually running the audit task against every plausible
    // candidate: given a file with four planted bugs it found 4/4 on six consecutive runs in
    // 4-7s, with clean JSON and no false-positive padding. minimax-m3 matched it for quality but
    // failed one run outright, and nemotron-3-super blew its token budget reasoning on another.
    // It is also a different lineage from Kimi, so the audit is a genuine second opinion.
    RoleKey.GLM -> "stepfun-ai/step-3.7-flash"
    RoleKey.DEEPSEEK -> "deepseek-ai/deepseek-v4-flash-0731"
    RoleKey.NEMOTRON -> "nvidia/nemotron-3-ultra-550b-a55b"
}

/**
 * Model ids that are dead specifically on NVIDIA NIM, mapped to a live NIM replacement.
 *
 * Changing defaultModelFor() alone does not reach anyone who already has the app: a saved
 * ProviderConfig outranks the default forever, so a user still holding a retired id would keep
 * failing on every build no matter what the shipped code says. This rewrites those ids on load
 * and touches nothing else the user chose.
 */
private val DEAD_ON_NIM = mapOf(
    // Both answer HTTP 410 on NIM with an explicit end-of-life date (5.1 on 2026-07-02, 5.2 on
    // 2026-08-21). glm-5.3 was never a NIM model at all — a wrong guess at a successor, and the
    // actual source of the 404 users were seeing on every build.
    "z-ai/glm-5.3" to "stepfun-ai/step-3.7-flash",
    "z-ai/glm-5.2" to "stepfun-ai/step-3.7-flash",
    "z-ai/glm-5.1" to "stepfun-ai/step-3.7-flash",
)

private fun isNimUrl(url: String): Boolean =
    url.contains("integrate.api.nvidia.com", ignoreCase = true)

/**
 * Applied once whenever settings are loaded from disk.
 *
 * Deliberately scoped to the endpoint, not the model id: these GLM versions are dead *on NIM*,
 * but perfectly alive on OpenRouter, where glm-5.2 is a normal paid model. Rewriting by id alone
 * would silently undo a user who picked the GLM preset for the auditor — and leave them worse off
 * than before, since it would point a NIM-only model id at OpenRouter's endpoint.
 */
fun AppSettings.migrated(): AppSettings {
    var changed = false
    // An install from before modes existed carries only an audit count; map it once so the chip
    // row opens on something that matches what they had rather than jumping to the default.
    var withMode = this
    if (buildMode.isBlank()) {
        withMode = copy(buildMode = modeForAuditLoops(maxAuditLoops).label)
        changed = true
    }
    val fixed = withMode.providers.mapValues { (_, cfg) ->
        val to = DEAD_ON_NIM[cfg.model.trim()]
        if (to == null || !isNimUrl(cfg.baseUrl)) cfg
        else { changed = true; cfg.copy(model = to) }
    }
    return if (changed) withMode.copy(providers = fixed) else this
}

fun defaultProviders(): Map<String, ProviderConfig> =
    ROLE_ORDER.associate { it.name to ProviderConfig(model = defaultModelFor(it)) }

/**
 * What "more power" actually means for a build.
 *
 * The old tiers changed exactly one thing — how many times the auditor re-read the same code —
 * while their names promised something much bigger. The deepest one's own description admitted
 * it: "Same models as every other tier, just the most passes over the same code." Re-reading is
 * a real lever but a weak one, and it hits diminishing returns fast; stacking eight names on it
 * made the dial feel arbitrary because it was.
 *
 * A mode now moves every lever that genuinely affects output quality at once:
 *
 *  - `maxTokens` — the big one. At 8k the coder silently truncates a large file mid-function and
 *    the audit then "fixes" a fragment. 32768 was verified live against the coder endpoint;
 *    65536 was not, so it is not offered.
 *  - `temperature` — lower is more deterministic, which is what you want for code, but too low
 *    on the first pass makes a model repeat a bad idea instead of finding another approach.
 *  - `audits` — the old dial, kept, but now one input among several.
 *  - `deepLogic` — brings in the reasoning model when coder and auditor deadlock.
 *  - `safetyNet` — a final independent review pass before anything ships.
 *
 * No mode promises bug-free output, and none of these names will. A model that writes code that
 * always compiles and never has a defect does not exist; claiming otherwise in a tier label just
 * moves the disappointment later.
 */
data class BuildMode(
    val label: String,
    val audits: Int,
    val maxTokens: Int,
    val temperature: Double,
    val deepLogic: Boolean,
    val safetyNet: Boolean,
    val description: String,
)

val BUILD_MODES = listOf(
    BuildMode(
        "FAST", audits = 0, maxTokens = 8_192, temperature = 0.45,
        deepLogic = false, safetyNet = false,
        description = "One pass, nothing checks it. Seconds, not minutes — for a throwaway script or a quick look at an idea.",
    ),
    BuildMode(
        "BALANCED", audits = 2, maxTokens = 12_288, temperature = 0.30,
        deepLogic = false, safetyNet = true,
        description = "The default. Two audit rounds plus a final safety review, and enough token room that a normal-sized file finishes properly.",
    ),
    BuildMode(
        "DEEP", audits = 4, maxTokens = 20_480, temperature = 0.20,
        deepLogic = true, safetyNet = true,
        description = "Four audits, lower temperature for more predictable code, and the reasoning model steps in if the coder and auditor deadlock.",
    ),
    BuildMode(
        // Audits dropped 6 -> 3 after timing a real ULTRA run. Each round that finds something
        // re-runs the coder, and the coder is the slow part: measured end to end on NIM, one
        // build of a single-file Pomodoro app streamed 612 lines in 965s — about 5.6 tokens a
        // second. Six rounds of that is potentially an hour of waiting on a phone, which is not
        // a "power" setting, it is an abandoned build. Three still gives the auditor real chances
        // while keeping the worst case somewhere a person will actually sit through.
        "ULTRA", audits = 3, maxTokens = 32_768, temperature = 0.10,
        deepLogic = true, safetyNet = true,
        description = "Everything on, full token budget so a big project doesn't get cut short. Expect 15+ minutes, sometimes far longer — the coder itself is slow, and every audit that finds something runs it again. Still no guarantee of zero bugs; nothing gives you that.",
    ),
)

/** Old saved settings stored an audit count; map it onto the nearest mode. */
fun modeForAuditLoops(n: Int): BuildMode =
    BUILD_MODES.minByOrNull { kotlin.math.abs(it.audits - n) } ?: BUILD_MODES[1]


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

/**
 * One file the swarm actually touched on this turn, with the real line counts of what changed.
 * Rendered in the chat as a compact "Created app/page.tsx +110 -0" row so the work is visible as
 * it lands, instead of only existing inside the project panel.
 */
@Serializable
data class FileAction(
    val path: String,
    /** "Created" or "Edited" — derived from whether the file existed before this turn. */
    val verb: String,
    val added: Int,
    val removed: Int,
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
    /** Files this turn created or changed, with real +/- line counts. */
    val fileActions: List<FileAction> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    /** Which model actually produced this reply — "Fast Chat", a forced role's label, or "Deep Research". */
    val modelUsed: String? = null,
    /** A real file on this device that this message is offering — currently a built APK. */
    val attachment: MessageFile? = null,
)

/**
 * A finished file sitting in the app's own storage, ready to be opened or shared. Held by path
 * rather than by bytes so a 20MB APK never goes near the conversation JSON; [exists] is what the
 * UI checks, since the cache directory can be cleared by Android at any time.
 */
@Serializable
data class MessageFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    /** "apk" today. Kept explicit so the row can say the right thing for other kinds later. */
    val kind: String = "apk",
) {
    fun exists(): Boolean = java.io.File(path).let { it.isFile && it.length() > 0 }

    fun prettySize(): String = when {
        sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
        sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
        else -> "$sizeBytes B"
    }
}

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
