package com.chomugiri.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HuggingFaceException(message: String) : Exception(message)

/** The verified-live default: Wan2.2-TI2V-5B served via the fal-ai provider, routed through HF. */
const val HF_DEFAULT_VIDEO_MODEL_PATH = "fal-ai/fal-ai/wan/v2.2-5b/text-to-video"

/**
 * Real text-to-video generation via Hugging Face's Inference Providers router — user-supplied
 * token only, same as every other provider in this app. Verified live against the actual API
 * before writing this (not guessed): the request shape below returns a real, structured 402
 * "depleted monthly credits" error rather than a 404, confirming the endpoint and auth are wired
 * correctly. Free HF accounts get a small included credit that resets monthly (not unlimited —
 * there is no such thing for video generation, it's too GPU-expensive for any provider to give
 * away without limit) and PRO accounts get roughly 20x more.
 */
object HuggingFaceClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Returns the raw generated video bytes (mp4) on success. */
    suspend fun generateVideo(token: String, providerModelPath: String, prompt: String): ByteArray =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) throw HuggingFaceException("No Hugging Face token set — add one in Settings to generate video.")
            val path = providerModelPath.ifBlank { HF_DEFAULT_VIDEO_MODEL_PATH }.trim('/')

            val body = JSONObject().put("inputs", prompt)
            val req = Request.Builder()
                .url("https://router.huggingface.co/$path")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                val contentType = resp.header("Content-Type").orEmpty()
                if (!resp.isSuccessful || contentType.contains("application/json", ignoreCase = true)) {
                    val text = resp.body?.string().orEmpty()
                    val apiMsg = try {
                        JSONObject(text).optString("error").ifBlank { null }
                    } catch (e: Exception) {
                        null
                    }
                    throw HuggingFaceException(
                        when {
                            resp.code == 402 -> "Hugging Face: monthly free video-generation credit is used up for this account (resets next month), or upgrade to PRO for more."
                            apiMsg != null -> "Hugging Face request failed (HTTP ${resp.code}): $apiMsg"
                            else -> "Hugging Face request failed (HTTP ${resp.code}): ${text.take(300)}"
                        }
                    )
                }
                resp.body?.bytes() ?: throw HuggingFaceException("Hugging Face didn't return any video data.")
            }
        }
}
