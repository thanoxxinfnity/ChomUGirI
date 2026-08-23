package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.GeminiClient

/** The convention Kimi is prompted to use wherever a real photo/illustration belongs. */
private val IMAGE_MARKER = Regex("\\{\\{IMAGE:\\s*([^}]{1,200}?)\\s*\\}\\}")

/** How many distinct images one project will ever generate — keeps a run bounded in time and cost. */
private const val MAX_IMAGES = 6

/**
 * A deterministic placeholder SVG (as a data URI) so a project never ships a literal
 * "{{IMAGE: ...}}" string on the page — used when no Gemini key is set, or a generation call
 * fails. Not a real photo, but never a broken/blank spot either.
 *
 * Deliberately dark and on-palette rather than a random hue. An earlier version picked its hue
 * from the description's hashCode, which on a real generated page produced a row of clashing
 * pink/olive/orange rectangles against the dark theme — worse-looking than having no image at
 * all. These stay inside the design system's own range (see WebScaffold.kt), varying only
 * subtly, so a page missing its photos still reads as one coherent design.
 */
private fun placeholderDataUri(description: String): String {
    // Narrow band around the theme's violet/cyan accents, not the full colour wheel.
    val h = description.hashCode().and(0x7fffffff)
    val hue = 232 + (h % 38)           // 232-269: indigo -> violet
    val hue2 = 186 + (h / 7 % 30)      // 186-215: cyan -> blue
    val cx = 28 + (h / 13 % 45)        // move the glow around so tiles differ
    val cy = 26 + (h / 29 % 40)
    val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600">
          <defs>
            <radialGradient id="glow" cx="$cx%" cy="$cy%" r="72%">
              <stop offset="0%" stop-color="hsl($hue,72%,58%)" stop-opacity=".42"/>
              <stop offset="55%" stop-color="hsl($hue2,68%,45%)" stop-opacity=".16"/>
              <stop offset="100%" stop-color="hsl($hue,60%,40%)" stop-opacity="0"/>
            </radialGradient>
            <linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#12141c"/>
              <stop offset="100%" stop-color="#08090c"/>
            </linearGradient>
          </defs>
          <rect width="800" height="600" fill="url(#base)"/>
          <rect width="800" height="600" fill="url(#glow)"/>
          <rect x="1" y="1" width="798" height="598" fill="none"
                stroke="hsl($hue,60%,70%)" stroke-opacity=".16" stroke-width="2"/>
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
                onProgress("Image generation failed: ${e.message ?: "unknown error"} — using a placeholder. (Settings > Image Generation > Test the key if this keeps happening.)")
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
