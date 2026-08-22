package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.HuggingFaceClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A separate, explicit tool (not folded into the auto-router) — same shape as the PPTX
 * generator. Runs one real Hugging Face Inference Providers call and ships the actual returned
 * video bytes as a base64 GeneratedFile — see GeneratedFile.encoding / rawBytes(). Nothing here
 * is simulated: a missing token or a real provider error (no credits, model cold-starting, etc.)
 * surfaces honestly instead of a placeholder video.
 */
fun runVideoPipeline(prompt: String, settings: AppSettings): Flow<PipelineEvent> = flow {
    try {
        if (settings.huggingfaceToken.isBlank()) {
            emit(PipelineEvent.Failed("No Hugging Face token set — add one in Settings > Video Generation to generate video."))
            return@flow
        }
        emit(PipelineEvent.Step("Generating video via Hugging Face (this can take a minute or two)..."))
        val bytes = HuggingFaceClient.generateVideo(settings.huggingfaceToken, settings.huggingfaceVideoModel, prompt)
        if (bytes.isEmpty()) {
            emit(PipelineEvent.Failed("Hugging Face didn't return any video data."))
            return@flow
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val fileName = prompt.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "video" }
        val file = GeneratedFile(path = "$fileName.mp4", content = b64, encoding = "base64")

        emit(PipelineEvent.Files(listOf(file)))
        emit(PipelineEvent.Step("Done — ${bytes.size / 1024} KB of video.", done = true))
        emit(PipelineEvent.Done(listOf(file), "Hugging Face (video)"))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Couldn't generate the video."))
    }
}
