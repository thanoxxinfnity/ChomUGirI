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

/**
 * The only routing signal in the app — there is no manual mode switch. Trivial chat never wakes
 * the heavy swarm; an explicit research ask goes to Deep Research; anything that describes
 * something to build goes to the pipeline; talking through an idea stays chat so the fast model
 * can actually ask what the user wants, instead of the swarm silently starting to write files.
 */
fun classifyIntent(message: String): Intent {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return Intent.CHAT
    if (trimmed.length < 40 && GREETING.containsMatchIn(trimmed)) return Intent.CHAT

    val lower = trimmed.lowercase()

    if (DISCUSSION_PHRASES.any { lower.contains(it) }) return Intent.CHAT

    // Research wins over code only when it is clearly an information ask, not a build ask.
    val hasCodeKeyword = CODE_KEYWORDS.any { lower.contains(it) }
    val hasResearchKeyword = RESEARCH_KEYWORDS.any { lower.contains(it) }
    if (hasResearchKeyword && !hasCodeKeyword) return Intent.RESEARCH

    if (hasCodeKeyword || trimmed.length > 180) return Intent.PIPELINE
    return Intent.CHAT
}
