package com.chomugiri.app.core

enum class Intent { CHAT, PIPELINE, RESEARCH }

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
 * The only routing signal in the app — there is no manual mode switch. Trivial chat never wakes
 * the heavy swarm; an explicit research ask goes to Deep Research; anything that describes
 * something to build goes to the pipeline.
 */
fun classifyIntent(message: String): Intent {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return Intent.CHAT
    if (trimmed.length < 40 && GREETING.containsMatchIn(trimmed)) return Intent.CHAT

    val lower = trimmed.lowercase()

    // Research wins over code only when it is clearly an information ask, not a build ask.
    val hasCodeKeyword = CODE_KEYWORDS.any { lower.contains(it) }
    val hasResearchKeyword = RESEARCH_KEYWORDS.any { lower.contains(it) }
    if (hasResearchKeyword && !hasCodeKeyword) return Intent.RESEARCH

    if (hasCodeKeyword || trimmed.length > 180) return Intent.PIPELINE
    return Intent.CHAT
}
