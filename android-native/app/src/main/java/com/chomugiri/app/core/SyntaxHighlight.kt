package com.chomugiri.app.core

/**
 * A small, dependency-free highlighter: one shared ruleset (strings, comments, numbers, common
 * keywords) applied by regex, good enough to make generated code readable without pulling in a
 * real language-server-grade highlighter. Returns spans as (range, tokenKind) so the UI decides
 * the actual colors.
 */
enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, TAG }

data class HighlightSpan(val range: IntRange, val kind: TokenKind)

private val KEYWORDS = setOf(
    "function", "const", "let", "var", "return", "if", "else", "for", "while", "class", "new",
    "import", "export", "default", "from", "async", "await", "try", "catch", "finally", "throw",
    "def", "elif", "print", "None", "True", "False", "self", "fun", "val", "when", "object",
    "interface", "extends", "implements", "public", "private", "static", "void", "int", "String",
    "boolean", "null", "true", "false", "this", "typeof", "instanceof", "package",
)

private val STRING_RE = Regex("""("([^"\\]|\\.)*")|('([^'\\]|\\.)*')|(`([^`\\]|\\.)*`)""")
private val LINE_COMMENT_RE = Regex("""//[^\n]*|#[^\n]*""")
private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/|<!--[\s\S]*?-->""")
private val NUMBER_RE = Regex("""\b\d+(\.\d+)?\b""")
private val HTML_TAG_RE = Regex("""</?[a-zA-Z][a-zA-Z0-9-]*""")
private val KEYWORD_RE = Regex("""\b(${KEYWORDS.joinToString("|")})\b""")

/**
 * Finds all spans in priority order (comments/strings win over keywords inside them) and returns
 * a flat, non-overlapping list sorted by start offset.
 */
fun highlightSpans(code: String): List<HighlightSpan> {
    val taken = BooleanArray(code.length)
    val spans = mutableListOf<HighlightSpan>()

    fun claim(range: IntRange, kind: TokenKind) {
        if (range.first < 0 || range.last >= code.length) return
        for (i in range) if (taken[i]) return
        for (i in range) taken[i] = true
        spans += HighlightSpan(range, kind)
    }

    // Highest priority first: block comments, then line comments, then strings, so a "//" inside
    // a string literal is never mistaken for a comment.
    BLOCK_COMMENT_RE.findAll(code).forEach { claim(it.range, TokenKind.COMMENT) }
    STRING_RE.findAll(code).forEach { claim(it.range, TokenKind.STRING) }
    LINE_COMMENT_RE.findAll(code).forEach { claim(it.range, TokenKind.COMMENT) }
    HTML_TAG_RE.findAll(code).forEach { claim(it.range, TokenKind.TAG) }
    KEYWORD_RE.findAll(code).forEach { claim(it.range, TokenKind.KEYWORD) }
    NUMBER_RE.findAll(code).forEach { claim(it.range, TokenKind.NUMBER) }

    return spans.sortedBy { it.range.first }
}
