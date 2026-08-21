package com.chomugiri.app.core

private val LINK_TAG = Regex(
    """<link\b[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*/?>|<link\b[^>]*href=["']([^"']+)["'][^>]*rel=["']stylesheet["'][^>]*/?>""",
    RegexOption.IGNORE_CASE,
)
private val SCRIPT_SRC_TAG = Regex("""<script\b[^>]*\bsrc=["']([^"']+)["'][^>]*></script>""", RegexOption.IGNORE_CASE)

/**
 * Turns a generated web project into one self-contained HTML string a WebView can render with
 * `loadDataWithBaseURL` — no local server needed. `<link rel="stylesheet" href="local.css">` and
 * `<script src="local.js">` pointing at files that are actually part of the project get inlined;
 * anything pointing off-project (http/https, a CDN) is left alone since the WebView can fetch
 * that itself.
 */
fun buildLivePreviewHtml(files: List<GeneratedFile>): String? {
    val byPath = files.associateBy { it.path.trimStart('/') }
    val entry = files.firstOrNull { it.path.equals("index.html", ignoreCase = true) }
        ?: files.firstOrNull { it.path.endsWith(".html", ignoreCase = true) }
        ?: return null

    var html = entry.content

    html = LINK_TAG.replace(html) { m ->
        val href = (m.groupValues[1].ifBlank { m.groupValues[2] })
        val file = resolveRelative(href, byPath)
        if (file != null) "<style>\n${file.content}\n</style>" else m.value
    }

    html = SCRIPT_SRC_TAG.replace(html) { m ->
        val src = m.groupValues[1]
        val file = resolveRelative(src, byPath)
        if (file != null) "<script>\n${file.content}\n</script>" else m.value
    }

    return html
}

private fun resolveRelative(href: String, byPath: Map<String, GeneratedFile>): GeneratedFile? {
    if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) return null
    val cleaned = href.removePrefix("./").trimStart('/')
    return byPath[cleaned]
}

/** True when the project has something a live preview could actually render. */
fun hasPreviewableEntry(files: List<GeneratedFile>): Boolean =
    files.any { it.path.endsWith(".html", ignoreCase = true) }
