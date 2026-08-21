package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.GeminiClient

/** The convention Kimi is prompted to use wherever a real photo/illustration belongs. */
private val IMAGE_MARKER = Regex("\\{\\{IMAGE:\\s*([^}]{1,200}?)\\s*\\}\\}")

/** How many distinct images one project will ever generate — keeps a run bounded in time and cost. */
private const val MAX_IMAGES = 6

/**
 * A soft, deterministic gradient SVG (as a data URI) so a project never ships a literal
 * "{{IMAGE: ...}}" string on the page — used when no Gemini key is set, or a generation call
 * fails. Not a real photo, but never a broken/blank spot either.
 */
private fun placeholderDataUri(description: String): String {
    val hue = (description.hashCode().and(0x7fffffff)) % 360
    val hue2 = (hue + 40) % 360
    val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">
          <defs>
            <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="hsl($hue,55%,55%)"/>
              <stop offset="100%" stop-color="hsl($hue2,55%,40%)"/>
            </linearGradient>
          </defs>
          <rect width="800" height="600" fill="url(#g)"/>
        </svg>
    """.trimIndent()
    val b64 = Base64.encodeToString(svg.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "data:image/svg+xml;base64,$b64"
}

/**
 * Finds every {{IMAGE: description}} marker across the project's files and replaces it with a
 * real image (Gemini, if a key is configured) or a graceful gradient fallback — so nothing ever
 * ships with a broken placeholder string visible on the page.
 */
suspend fun resolveImageMarkers(
    files: List<GeneratedFile>,
    settings: AppSettings,
    onProgress: suspend (String) -> Unit = {},
): List<GeneratedFile> {
    val descriptions = LinkedHashSet<String>()
    files.forEach { f -> IMAGE_MARKER.findAll(f.content).forEach { descriptions += it.groupValues[1].trim() } }
    if (descriptions.isEmpty()) return files

    val resolved = LinkedHashMap<String, String>()
    descriptions.take(MAX_IMAGES).forEach { desc ->
        resolved[desc] = if (settings.geminiApiKey.isNotBlank()) {
            try {
                onProgress("Generating image: ${desc.take(60)}...")
                GeminiClient.generateImageDataUri(settings.geminiApiKey, settings.geminiModel, desc)
            } catch (e: Exception) {
                onProgress("Image generation failed (${e.message?.take(120)}) — using a placeholder.")
                placeholderDataUri(desc)
            }
        } else {
            placeholderDataUri(desc)
        }
    }
    // Any marker beyond the cap still needs *something* in its place.
    descriptions.drop(MAX_IMAGES).forEach { resolved[it] = placeholderDataUri(it) }

    return files.map { f ->
        if (!IMAGE_MARKER.containsMatchIn(f.content)) return@map f
        val newContent = IMAGE_MARKER.replace(f.content) { m -> resolved[m.groupValues[1].trim()] ?: placeholderDataUri(m.groupValues[1]) }
        f.copy(content = newContent)
    }
}
