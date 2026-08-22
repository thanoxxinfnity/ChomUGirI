package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.GeminiClient
import com.chomugiri.app.net.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private data class PlannedSlide(val title: String, val bullets: List<String>, val imageDesc: String)

private fun parseOutline(obj: org.json.JSONObject?): Pair<String, List<PlannedSlide>> {
    if (obj == null) return "Presentation" to emptyList()
    val title = obj.optString("title", "Presentation").ifBlank { "Presentation" }
    val arr = obj.optJSONArray("slides")
    val slides = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slideTitle = o.optString("title")
            if (slideTitle.isBlank()) continue
            val bullets = buildList {
                val bArr = o.optJSONArray("bullets")
                if (bArr != null) for (j in 0 until bArr.length()) {
                    val b = bArr.optString(j)
                    if (b.isNotBlank()) add(b)
                }
            }
            add(PlannedSlide(slideTitle, bullets, o.optString("image")))
        }
    }
    return title to slides
}

/**
 * A separate, explicit tool (not folded into the auto-router) — the user asked for PPT
 * generation as its own option. Plans a real outline via a real model call, generates real
 * images for the slides that ask for one (when a Gemini key is set — skipped honestly otherwise,
 * never a fake placeholder baked into a binary file), then builds a genuinely valid .pptx via
 * PptxBuilder. The result ships as a base64-encoded GeneratedFile since a .pptx is a real ZIP
 * archive, not text — see GeneratedFile.encoding / rawBytes().
 */
fun runPptxPipeline(prompt: String, settings: AppSettings): Flow<PipelineEvent> = flow {
    try {
        emit(PipelineEvent.Step("Planning your slides..."))
        val raw = LlmClient.complete(
            settings.provider(RoleKey.FAST), "Fast Chat",
            listOf(ChatTurn("system", PPTX_OUTLINE_PROMPT), ChatTurn("user", prompt)),
            temperature = 0.5, maxTokens = 3000, jsonMode = true,
        )
        val (deckTitle, planned) = parseOutline(extractJsonObject(raw))
        if (planned.isEmpty()) {
            emit(PipelineEvent.Failed("Couldn't plan a slide outline from that — try describing the topic in a bit more detail."))
            return@flow
        }
        emit(PipelineEvent.Step("Outline ready: ${planned.size} slide(s).", done = true))

        val hasGeminiKey = settings.geminiApiKey.isNotBlank()
        val slides = planned.mapIndexed { i, s ->
            var imgBytes: ByteArray? = null
            var isJpeg = false
            if (s.imageDesc.isNotBlank()) {
                if (hasGeminiKey) {
                    emit(PipelineEvent.Step("Generating image for slide ${i + 1}/${planned.size}..."))
                    try {
                        val dataUri = GeminiClient.generateImageDataUri(settings.geminiApiKey, settings.geminiModel, s.imageDesc)
                        isJpeg = dataUri.startsWith("data:image/jpeg")
                        imgBytes = GeminiClient.decodeDataUri(dataUri)
                    } catch (e: Exception) {
                        emit(PipelineEvent.Step("Slide ${i + 1} image failed: ${e.message ?: "unknown error"} — shipping that slide text-only.", done = true))
                    }
                } else {
                    emit(PipelineEvent.Step("Slide ${i + 1} wants an image, but no Gemini API key is set (Settings > Image Generation) — shipping text-only.", done = true))
                }
            }
            PptxSlide(s.title, s.bullets, imgBytes, isJpeg)
        }

        emit(PipelineEvent.Step("Assembling the .pptx file..."))
        val bytes = PptxBuilder.build(slides)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val fileName = deckTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "presentation" }
        val file = GeneratedFile(path = "$fileName.pptx", content = b64, encoding = "base64")

        emit(PipelineEvent.Files(listOf(file)))
        emit(PipelineEvent.Step("Done — ${slides.size} slide(s), ${slides.count { it.imageBytes != null }} with a real image.", done = true))
        emit(PipelineEvent.Done(listOf(file), "PPTX Generator"))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Couldn't generate the presentation."))
    }
}
