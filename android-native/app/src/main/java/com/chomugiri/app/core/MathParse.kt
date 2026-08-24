package com.chomugiri.app.core

/**
 * A LaTeX subset, parsed into a tree the UI can lay out as real mathematics rather than printing
 * the source. Chat answers arrive full of `\frac{4350 \times 100}{120}`, which is unreadable as
 * plain text and is exactly what a maths answer needs to look right.
 *
 * Deliberately a subset. It covers what actually turns up in the arithmetic, algebra and
 * school-science answers this app is asked for — fractions, powers, indices, roots, and the
 * operator/Greek symbol set — and it degrades to plain text for anything past that rather than
 * failing. Integrals, matrices and alignment environments are not handled; they render as their
 * own source, which is ugly but honest, and never crashes or drops the content.
 */
sealed class MathNode {
    data class Sym(val text: String) : MathNode()
    data class Row(val children: List<MathNode>) : MathNode()
    data class Frac(val num: MathNode, val den: MathNode) : MathNode()
    data class Sup(val base: MathNode, val exp: MathNode) : MathNode()
    data class Sub(val base: MathNode, val sub: MathNode) : MathNode()
    data class Sqrt(val inner: MathNode) : MathNode()
}

/** LaTeX command -> the character it actually means. */
private val SYMBOLS = mapOf(
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
    "approx" to "≈", "equiv" to "≡", "propto" to "∝", "infty" to "∞",
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "leftrightarrow" to "↔", "implies" to "⇒",
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "phi" to "φ", "omega" to "ω",
    "Delta" to "Δ", "Sigma" to "Σ", "Omega" to "Ω", "Theta" to "Θ", "Pi" to "Π",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "partial" to "∂", "nabla" to "∇",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩",
    "forall" to "∀", "exists" to "∃", "therefore" to "∴", "because" to "∵",
    "degree" to "°", "circ" to "∘", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
    "ldots" to "…", "dots" to "…", "cdots" to "⋯",
    "percent" to "%", "rupee" to "₹",
    // Spacing commands carry no glyph; they must still be consumed so they don't print raw.
    "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", "!" to "",
    // Escaped literals. Without these a perfectly ordinary "7\\%" printed as "7\\%" — caught by
    // the parser tests, which is exactly the kind of thing that looks fine until it is on screen.
    "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_", "{" to "{", "}" to "}",
    "left" to "", "right" to "", "displaystyle" to "", "text" to "", "mathrm" to "",
)

private class MathParser(private val src: String) {
    private var i = 0

    fun parse(): MathNode = Row(parseUntil(null))

    private fun Row(children: List<MathNode>) =
        if (children.size == 1) children[0] else MathNode.Row(children)

    /** Reads nodes until [stop] (an unescaped closing brace) or the end of the input. */
    private fun parseUntil(stop: Char?): List<MathNode> {
        val out = mutableListOf<MathNode>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) { out += MathNode.Sym(plain.toString()); plain.clear() }
        }

        while (i < src.length) {
            val c = src[i]
            if (stop != null && c == stop) break
            when {
                c == '\\' -> {
                    val cmd = readCommand()
                    when (cmd) {
                        "frac", "dfrac", "tfrac" -> {
                            flush()
                            val n = readGroup(); val d = readGroup()
                            out += MathNode.Frac(n, d)
                        }
                        "sqrt" -> { flush(); out += MathNode.Sqrt(readGroup()) }
                        else -> {
                            // \text{...} and \mathrm{...} keep their contents as literal text.
                            if ((cmd == "text" || cmd == "mathrm") && peek() == '{') {
                                flush(); out += readGroup()
                            } else {
                                // An unknown command keeps its own source rather than vanishing —
                                // losing the user's content silently is worse than showing it raw.
                                plain.append(SYMBOLS[cmd] ?: "\\$cmd")
                            }
                        }
                    }
                }
                c == '^' || c == '_' -> {
                    i++
                    // A script binds to the single token immediately before it. Pulling that from
                    // the pending text run when there is one — and only falling back to the last
                    // built node when there isn't — is what the tests forced: taking from `out`
                    // first turned "x^2 + y^2 = z^2" into "x^2^2^2 + y = z", because after the
                    // first script the previous Sup was sitting in `out` and got stolen.
                    val base: MathNode
                    if (plain.isNotEmpty()) {
                        base = MathNode.Sym(plain.last().toString())
                        plain.deleteCharAt(plain.length - 1)
                        flush()
                    } else {
                        base = out.removeLastOrNull() ?: MathNode.Sym("")
                    }
                    val script = readGroup()
                    out += if (c == '^') MathNode.Sup(base, script) else MathNode.Sub(base, script)
                }
                c == '{' -> { flush(); out += readGroup() }
                c == '}' -> { i++ } // stray close brace: skip rather than derail the parse
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun peek(): Char? = src.getOrNull(i)

    private fun readCommand(): String {
        i++ // the backslash
        if (i >= src.length) return ""
        // A non-letter command is one character long: \, \; \! and friends.
        if (!src[i].isLetter()) return src[i].toString().also { i++ }
        val start = i
        while (i < src.length && src[i].isLetter()) i++
        return src.substring(start, i)
    }

    /** `{...}` if present, otherwise the single next token — LaTeX's own rule for x^2 vs x^{12}. */
    private fun readGroup(): MathNode {
        while (i < src.length && src[i] == ' ') i++
        if (i >= src.length) return MathNode.Sym("")
        if (src[i] == '{') {
            i++
            val inner = parseUntil('}')
            if (i < src.length && src[i] == '}') i++
            return Row(inner)
        }
        if (src[i] == '\\') {
            val cmd = readCommand()
            return MathNode.Sym(SYMBOLS[cmd] ?: "\\$cmd")
        }
        return MathNode.Sym(src[i].toString()).also { i++ }
    }
}

fun parseMath(latex: String): MathNode = MathParser(latex.trim()).parse()

/** Matches $$display$$, $inline$, and \( \) / \[ \] — the four wrappers models actually emit. */
val MATH_SPAN = Regex("""\$\$(.+?)\$\$|\\\[(.+?)\\]|\$(.+?)\$|\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)

/**
 * True when a `$` run is really maths rather than a price. "₹230 each" and "$5 and $10" must not
 * become equations — this app's users write about rupees constantly, and a false positive turns a
 * normal sentence into mangled symbols.
 */
fun looksLikeMath(body: String): Boolean {
    if (body.isBlank()) return false
    if (body.length > 400) return false
    if (body.any { it == '\n' } && !body.contains('\\')) return false
    return body.contains('\\') || body.contains('^') || body.contains('_') ||
        body.any { it in "=<>+×÷≤≥≠" } || body.trim().all { it.isDigit() || it in " .,()/*-" }
}
