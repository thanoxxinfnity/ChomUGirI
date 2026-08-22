package com.chomugiri.app.core

import com.chomugiri.app.net.LlmClient

// PPTX and VIDEO are never auto-detected by the router below — the user picks them explicitly
// from the composer's "+" tools menu, the same way Deep Research is opted into rather than
// guessed at.
enum class Intent { CHAT, PIPELINE, RESEARCH, PPTX, VIDEO }

private val CODE_KEYWORDS = listOf(
    "app", "website", "web app", "webapp", "build", "banao", "bana do", "bana ke do",
    "bana kar do", "banaye", "banaiye", "banwa do", "likh do", "code likho", "code",
    "script", "program", "component", "api", "backend", "frontend", "database", "function",
    "class ", "bug", "fix kar", "error", "deploy", "landing page", "game", "clone",
    "feature add", "refactor", "convert", "generate a", "make me a", "create a", "crud",
    "endpoint", "dashboard", "chatbot", "calculator", "to-do", "todo list", "portfolio site",
    "e-commerce", "ecommerce", "html", "css", "sql", "python", "javascript", "typescript",
    "react", "vue", "angular", "flutter", "kotlin", "swift", "golang", "rust", "next.js",
    "nodejs", "node.js",
)

private val RESEARCH_KEYWORDS = listOf(
    "deep research", "research", "compare", "comparison", "latest", "news", "who is",
    "what is the latest", "find out", "look up", "search for", "pros and cons",
    "khoj", "pata karo", "dhoondh", "kya chal raha", "current price", "in 2026",
)

private val GREETING = Regex(
    "^(hi|hii+|hey|hello|salam|assalam|kya haal|kaise ho|good\\s?(morning|evening|night))\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * "I want to build X, let's talk it through first" is not the same ask as "build X now" — the
 * user wants to plan/discuss, not have the swarm start writing files. These phrases keep a
 * message in chat even when it also contains a code keyword like "app" or "banao".
 */
private val DISCUSSION_PHRASES = listOf(
    "plan kar", "plan banate", "iske baare mein", "iske bare mein", "discuss kar",
    "baat karte", "baat kar lete", "soch rahe", "sochte hain", "idea de", "kya lagta",
    "kya khayal", "pehle discuss", "pehle baat", "planning kar", "let's plan", "let's discuss",
    "what do you think", "before we build", "before building",
)

/** Matches a keyword as a whole word/phrase, not as a substring — "api" must not match inside "capital". */
private fun containsKeyword(text: String, keyword: String): Boolean {
    val escaped = Regex.escape(keyword.trim())
    return Regex("(?<![a-z0-9])$escaped(?![a-z0-9])").containsMatchIn(text)
}

/**
 * Local, offline fallback only — used when the AI triage call itself fails (no key, no network).
 * Real routing normally goes through [classifyIntentAi] below; this heuristic is deliberately
 * conservative and word-boundary-aware so a word like "capital" can never match the "api" keyword.
 */
fun classifyIntent(message: String): Intent {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return Intent.CHAT
    if (trimmed.length < 40 && GREETING.containsMatchIn(trimmed)) return Intent.CHAT

    val lower = trimmed.lowercase()

    if (DISCUSSION_PHRASES.any { lower.contains(it) }) return Intent.CHAT

    // Research wins over code only when it is clearly an information ask, not a build ask.
    val hasCodeKeyword = CODE_KEYWORDS.any { containsKeyword(lower, it) }
    val hasResearchKeyword = RESEARCH_KEYWORDS.any { containsKeyword(lower, it) }
    if (hasResearchKeyword && !hasCodeKeyword) return Intent.RESEARCH

    if (hasCodeKeyword || trimmed.length > 180) return Intent.PIPELINE
    return Intent.CHAT
}

private const val TRIAGE_PROMPT = """You are a routing classifier for a coding assistant app. Read the user's message and answer with EXACTLY one word — no punctuation, no explanation:

CHAT — casual conversation, greetings, opinions, or a question that just wants an answer (including general-knowledge questions like "what's the capital of France" or "explain closures in JS")
PIPELINE — the user is asking, right now, to build/create/generate/fix an actual app, website, script, or program
RESEARCH — the user is explicitly asking to look up or research current real-world information (news, prices, comparisons, "what's the latest...")

If in doubt between CHAT and PIPELINE, prefer CHAT — only route to PIPELINE when the user clearly wants something built.

Message: "%s"

One word answer:"""

/**
 * The real routing signal: a single fast, cheap AI call (the Fast Chat role) reads the message
 * and decides — not a keyword list. This is what actually understands "what's the capital of
 * France" is a question, not a request to build something with an "API". Greetings still skip
 * the network round-trip since there's nothing to decide there.
 */
suspend fun classifyIntentAi(message: String, settings: AppSettings): Intent {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return Intent.CHAT
    if (trimmed.length < 40 && GREETING.containsMatchIn(trimmed)) return Intent.CHAT

    // The local heuristic's own CODE_KEYWORDS list is comprehensive and word-boundary-safe — when
    // it already sees a clear build request, trust it outright instead of routing through the AI
    // classifier first. In real use, a small/free Fast Chat model misjudged an unambiguous build
    // request ("make a real website of full information") as CHAT — the TRIAGE_PROMPT below even
    // biases it toward CHAT "when in doubt" — so it answered in prose with no files ever
    // generated. This short-circuit is the structural fix for that failure mode, not a guess.
    if (classifyIntent(trimmed) == Intent.PIPELINE) return Intent.PIPELINE

    return try {
        val raw = LlmClient.complete(
            settings.provider(RoleKey.FAST), "Router",
            listOf(ChatTurn("user", TRIAGE_PROMPT.format(trimmed.take(500)))),
            temperature = 0.0, maxTokens = 6,
        ).trim().uppercase()
        when {
            raw.startsWith("PIPELINE") -> Intent.PIPELINE
            raw.startsWith("RESEARCH") -> Intent.RESEARCH
            raw.startsWith("CHAT") -> Intent.CHAT
            else -> classifyIntent(trimmed)
        }
    } catch (e: Exception) {
        // No key set, or the network call itself failed — fall back to the local heuristic
        // rather than silently dropping the message.
        classifyIntent(trimmed)
    }
}
