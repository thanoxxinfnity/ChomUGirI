package com.chomugiri.app.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiException(message: String) : Exception(message)

/**
 * Real image generation via Google's Generative Language API — user-supplied key only, same as
 * every other provider in this app. The model id is a Settings field, not hardcoded, because
 * Google's image-capable model names change over time and this app has no way to know which one
 * is current on the user's account/region.
 */
object GeminiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Returns a `data:<mime>;base64,...` URI ready to drop straight into an <img src> or CSS url(). */
    suspend fun generateImageDataUri(apiKey: String, model: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw GeminiException("No Gemini API key set — add one in Settings to generate images.")
            val safeModel = model.ifBlank { "gemini-2.5-flash-image" }

            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        )
                    ),
                )
                // Google's image-capable Gemini models reject a responseModalities that asks for
                // IMAGE alone — TEXT has to be listed alongside it even though the text part is
                // discarded below, or the call itself 400s.
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw GeminiException("Gemini returned an unexpected response (HTTP ${resp.code}).")
                }
                if (!resp.isSuccessful) {
                    val apiMsg = json.optJSONObject("error")?.optString("message")
                    throw GeminiException(
                        if (apiMsg.isNullOrBlank()) "Gemini image request failed (HTTP ${resp.code}): ${text.take(300)}"
                        else "Gemini image request failed (HTTP ${resp.code}): $apiMsg"
                    )
                }
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: throw GeminiException("Gemini didn't return any image data.")

                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType", "image/png")
                    val data = inline.optString("data")
                    if (data.isNotBlank()) return@withContext "data:$mime;base64,$data"
                }
                throw GeminiException("Gemini's response didn't include an image — try a shorter, more concrete description.")
            }
        }

    /** Decoded bytes, for callers that need the raw image rather than a data URI (unused today, kept for completeness). */
    fun decodeDataUri(dataUri: String): ByteArray? {
        val b64 = dataUri.substringAfter("base64,", "")
        if (b64.isBlank()) return null
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
