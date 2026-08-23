package com.chomugiri.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NvidiaImageException(message: String) : Exception(message)

/** Image models verified to exist on NVIDIA's genai endpoints (a real 401, not a 404). */
val NIM_IMAGE_MODELS = listOf(
    "black-forest-labs/flux.1-dev" to "FLUX.1 dev — best quality",
    "black-forest-labs/flux.1-schnell" to "FLUX.1 schnell — fastest",
    "stabilityai/stable-diffusion-3-medium" to "Stable Diffusion 3 Medium",
    "stabilityai/stable-diffusion-xl" to "SDXL",
    "stabilityai/sdxl-turbo" to "SDXL Turbo — fastest SD",
)

const val NIM_DEFAULT_IMAGE_MODEL = "black-forest-labs/flux.1-dev"

/**
 * Real image generation on NVIDIA NIM, using the same nvapi- key the chat roles use.
 *
 * These live under ai.api.nvidia.com/v1/genai/... rather than the OpenAI-style
 * integrate.api.nvidia.com used for chat, and they are not listed in that endpoint's /models
 * catalog — which is why they are named explicitly above after probing each one.
 *
 * Two request shapes exist across the families (FLUX takes a flat `prompt`, the Stability models
 * take `text_prompts`), and the response has been seen keyed as `artifacts[].base64`, `image`, or
 * an OpenAI-style `data[].b64_json`. Both are handled rather than assumed, so a shape difference
 * degrades to a clear error instead of a silent failure.
 */
object NvidiaImageClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun buildBody(model: String, prompt: String): JSONObject =
        if (model.contains("flux", ignoreCase = true)) {
            JSONObject()
                .put("prompt", prompt)
                .put("mode", "base")
                .put("cfg_scale", 3.5)
                .put("width", 1024)
                .put("height", 1024)
                .put("seed", 0)
                .put("steps", if (model.contains("schnell", ignoreCase = true)) 4 else 30)
        } else {
            JSONObject()
                .put("text_prompts", JSONArray().put(JSONObject().put("text", prompt).put("weight", 1)))
                .put("cfg_scale", 5)
                .put("sampler", "K_EULER_ANCESTRAL")
                .put("seed", 0)
                .put("steps", if (model.contains("turbo", ignoreCase = true)) 4 else 25)
        }

    /** Pulls the base64 payload out of whichever response shape came back. */
    private fun extractBase64(json: JSONObject): String? {
        json.optJSONArray("artifacts")?.optJSONObject(0)?.optString("base64")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        json.optString("image").takeIf { it.isNotBlank() }?.let { return it }
        json.optJSONArray("data")?.optJSONObject(0)?.let { d ->
            d.optString("b64_json").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Returns a `data:image/...;base64,...` URI ready to drop into an <img src> or CSS url(). */
    suspend fun generateImageDataUri(apiKey: String, model: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw NvidiaImageException("No NVIDIA API key set for images — add one in Settings.")
            }
            val safeModel = model.ifBlank { NIM_DEFAULT_IMAGE_MODEL }.trim('/')
            val req = Request.Builder()
                .url("https://ai.api.nvidia.com/v1/genai/$safeModel")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .post(buildBody(safeModel, prompt).toString().toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw NvidiaImageException("NVIDIA image API returned an unexpected response (HTTP ${resp.code}): ${text.take(200)}")
                }
                if (!resp.isSuccessful) {
                    val detail = json.optString("detail").ifBlank { json.optString("message") }
                    throw NvidiaImageException(
                        if (detail.isNotBlank()) "NVIDIA image request failed (HTTP ${resp.code}): $detail"
                        else "NVIDIA image request failed (HTTP ${resp.code}): ${text.take(250)}"
                    )
                }
                val b64 = extractBase64(json)
                    ?: throw NvidiaImageException("NVIDIA returned no image data. Response keys: ${json.keys().asSequence().take(6).joinToString()}")
                if (b64.startsWith("data:")) b64 else "data:image/png;base64,$b64"
            }
        }
}
