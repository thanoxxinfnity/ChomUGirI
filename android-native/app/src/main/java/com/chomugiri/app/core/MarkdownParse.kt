package com.chomugiri.app.core

/**
 * Block-level markdown, parsed into something the UI can lay out.
 *
 * Chat answers arrive full of `## headings`, `**bold**`, `- bullets` and `| tables |`, and until
 * now every one of those printed as its own source. The whole readability gap between this app and
 * the ones people compare it to was that the formatting the model already emits was being thrown
 * on screen raw.
 *
 * Only block structure lives here; inline emphasis is handled at render time so it can produce
 * styled spans rather than more nodes.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    /** One list item. [ordered] picks the marker; [marker] carries the real number when ordered. */
    data class ListItem(val text: String, val ordered: Boolean, val marker: String, val depth: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    /** A `$$...$$` block on its own line — rendered centred and larger than inline maths. */
    data class MathDisplay(val latex: String) : MdBlock()
    data object Rule : MdBlock()
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
private val TABLE_SEP = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$""")

private fun tableCells(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()

    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out += MdBlock.Paragraph(t)
        para.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // A display-maths block can span several lines, so it is consumed before anything else
        // gets a chance to mistake its contents for a list or a rule.
        if (trimmed.startsWith("$$")) {
            flushPara()
            val body = StringBuilder(trimmed.removePrefix("$$"))
            if (!trimmed.removePrefix("$$").endsWith("$$")) {
                i++
                while (i < lines.size && !lines[i].contains("$$")) { body.append('\n').append(lines[i]); i++ }
                if (i < lines.size) body.append('\n').append(lines[i].substringBefore("$$"))
            }
            val latex = body.toString().removeSuffix("$$").trim()
            if (latex.isNotEmpty()) out += MdBlock.MathDisplay(latex)
            i++
            continue
        }

        when {
            trimmed.isEmpty() -> flushPara()

            RULE.matches(line) -> { flushPara(); out += MdBlock.Rule }

            HEADING.matches(line) -> {
                flushPara()
                val m = HEADING.find(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }

            // A table is only a table if the row under the header is a separator — otherwise a
            // sentence with pipes in it would be swallowed into a grid.
            trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) &&
                lines[i + 1].contains('-') -> {
                flushPara()
                val header = tableCells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += tableCells(lines[i]); i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            BULLET.matches(line) -> {
                flushPara()
                val m = BULLET.find(line)!!
                out += MdBlock.ListItem(m.groupValues[2].trim(), false, "•", m.groupValues[1].length / 2)
            }

            NUMBERED.matches(line) -> {
                flushPara()
                val m = NUMBERED.find(line)!!
                out += MdBlock.ListItem(m.groupValues[3].trim(), true, m.groupValues[2] + ".", m.groupValues[1].length / 2)
            }

            QUOTE.matches(line) -> {
                flushPara()
                out += MdBlock.Quote(QUOTE.find(line)!!.groupValues[1].trim())
            }

            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flushPara()
    return out
}

/** A run of inline text with the emphasis that applies to it. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** Non-null when this span is maths and should be laid out rather than printed. */
    val math: String? = null,
)

private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])|`([^`]+)`|""" +
        """\$\$(.+?)\$\$|\\\((.+?)\\\)|\$([^$\n]+?)\$""",
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * Splits one line into styled runs. Maths is only treated as maths when it actually looks like it
 * ([looksLikeMath]) — this app's users write about rupees constantly, and turning "$5 and $10"
 * into an equation would be a worse bug than not rendering maths at all.
 */
fun parseInline(text: String): List<InlineSpan> {
    val out = mutableListOf<InlineSpan>()
    var last = 0
    for (m in INLINE.findAll(text)) {
        val mathBody = m.groupValues[5].ifEmpty { m.groupValues[6] }.ifEmpty { m.groupValues[7] }
        val isMath = mathBody.isNotEmpty()
        if (isMath && !looksLikeMath(mathBody)) continue

        if (m.range.first > last) out += InlineSpan(text.substring(last, m.range.first))
        when {
            isMath -> out += InlineSpan("", math = mathBody.trim())
            m.groupValues[1].isNotEmpty() -> out += InlineSpan(m.groupValues[1], bold = true)
            m.groupValues[2].isNotEmpty() -> out += InlineSpan(m.groupValues[2], bold = true)
            m.groupValues[3].isNotEmpty() -> out += InlineSpan(m.groupValues[3], italic = true)
            m.groupValues[4].isNotEmpty() -> out += InlineSpan(m.groupValues[4], code = true)
        }
        last = m.range.last + 1
    }
    if (last < text.length) out += InlineSpan(text.substring(last))
    return out.filter { it.text.isNotEmpty() || it.math != null }
}
