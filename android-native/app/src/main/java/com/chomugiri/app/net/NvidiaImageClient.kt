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

/**
 * All of these exist as NVIDIA genai endpoints, but which ones a given account may actually call
 * varies — tested against a real key, only flux.1-dev returned an image (1024x1024 in ~5s);
 * stable-diffusion-xl, sdxl-turbo and stable-diffusion-3-medium each answered 404 "Not found for
 * account", and flux.1-schnell timed out twice without responding. So flux.1-dev leads and is the
 * default, and the rest are labelled as needing enabling rather than presented as equal choices.
 */
val NIM_IMAGE_MODELS = listOf(
    "black-forest-labs/flux.1-dev" to "FLUX.1 dev — best quality (verified working)",
    "black-forest-labs/flux.1-schnell" to "FLUX.1 schnell — faster, if your account has it",
    "stabilityai/stable-diffusion-3-medium" to "SD 3 Medium — needs account access",
    "stabilityai/stable-diffusion-xl" to "SDXL — needs account access",
    "stabilityai/sdxl-turbo" to "SDXL Turbo — needs account access",
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
            // schnell is a distilled, guidance-free model: NIM rejects any cfg_scale above 0 on
            // it outright ("Input should be less than or equal to 0", HTTP 422 — hit for real
            // while testing). dev takes normal guidance.
            val schnell = model.contains("schnell", ignoreCase = true)
            JSONObject()
                .put("prompt", prompt)
                .put("mode", "base")
                .put("cfg_scale", if (schnell) 0 else 3.5)
                .put("width", 1024)
                .put("height", 1024)
                .put("seed", 0)
                .put("steps", if (schnell) 4 else 30)
        } else {
            JSONObject()
                .put("text_prompts", JSONArray().put(JSONObject().put("text", prompt).put("weight", 1)))
                .put("cfg_scale", 5)
                .put("sampler", "K_EULER_ANCESTRAL")
                .put("seed", 0)
                .put("steps", if (model.contains("turbo", ignoreCase = true)) 4 else 25)
        }

    /**
     * The API doesn't name the format anywhere in the response, and it is NOT png — a real
     * FLUX.1-dev call came back as a JPEG (payload starting "/9j/", verified as a 1024x1024 JFIF
     * file). Labelling that as image/png happens to survive browser sniffing but breaks anywhere
     * strict, so the type is read from the payload's own magic bytes instead of assumed.
     */
    private fun mimeFor(b64: String): String = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("iVBORw0KGgo") -> "image/png"
        b64.startsWith("R0lGOD") -> "image/gif"
        b64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NvidiaImageException("NVIDIA image API returned an unexpected response (HTTP ${resp.code}): ${text.take(200)}")
                }
                if (!resp.isSuccessful) {
                    val detail = json.optString("detail").ifBlank { json.optString("message") }
                    // A 404 here means the model exists but isn't enabled on this account — an
                    // easy failure to misread as "the app is broken", so it says so plainly.
                    if (resp.code == 404) {
                        throw NvidiaImageException(
                            "\"$safeModel\" isn't enabled on your NVIDIA account. Pick a different " +
                                "model in Settings (FLUX.1 dev works on a standard account), or " +
                                "enable this one at build.nvidia.com."
                        )
                    }
                    throw NvidiaImageException(
                        if (detail.isNotBlank()) "NVIDIA image request failed (HTTP ${resp.code}): $detail"
                        else "NVIDIA image request failed (HTTP ${resp.code}): ${text.take(250)}"
                    )
                }
                val b64 = extractBase64(json)
                    ?: throw NvidiaImageException("NVIDIA returned no image data. Response keys: ${json.keys().asSequence().take(6).joinToString()}")
                if (b64.startsWith("data:")) b64 else "data:${mimeFor(b64)};base64,$b64"
            }
        }
}
