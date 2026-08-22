package com.chomugiri.app.net

import com.chomugiri.app.core.ChatTurn
import com.chomugiri.app.core.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmException(val role: String, message: String) : Exception(message)

/**
 * Generic OpenAI-compatible chat client — works against NVIDIA NIM, OpenRouter, or any custom
 * endpoint that speaks /chat/completions.
 *
 * Everything streams internally, even when the caller only wants the finished string. Slow
 * models (the 70B/550B roles routinely take 1-3 minutes) will drop an idle non-streaming
 * connection long before they answer, so streaming is what keeps the socket alive.
 */
object LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun buildBody(
        cfg: ProviderConfig,
        messages: List<ChatTurn>,
        temperature: Double,
        maxTokens: Int,
        jsonMode: Boolean,
    ): JSONObject {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", true)
        // Deliberately NOT sending response_format:json_object even when jsonMode is requested —
        // plenty of models behind an aggregator (NIM, OpenRouter) 400 on a response_format they
        // don't recognise. The prompt already demands JSON-only output, and extractJsonObject()
        // is lenient about parsing it back out, so this parameter buys nothing but fragility.
        return body
    }

    /**
     * A pooled keep-alive connection that OkHttp still thinks is alive but the mobile network has
     * actually already killed (a network handoff, doze, a flaky tower) surfaces as a raw TLS
     * error on the very next request — SSLException/"bad_record_mac"/"BAD_DECRYPT" being the
     * classic signature — not as a clean IOException up front. It is not our request that's bad;
     * retrying once on a fresh connection genuinely resolves it in the normal case.
     */
    private fun isRetryableNetworkError(e: Throwable): Boolean = when (e) {
        is javax.net.ssl.SSLException, is java.net.SocketException,
        is java.net.SocketTimeoutException, is java.io.EOFException -> true
        else -> false
    }

    /** Streams content deltas as they arrive. */
    fun stream(
        cfg: ProviderConfig,
        role: String,
        messages: List<ChatTurn>,
        temperature: Double = 0.4,
        maxTokens: Int = 8192,
        jsonMode: Boolean = false,
    ): Flow<String> = flow {
        // Pollinations' free tier is genuinely keyless and speaks a completely different (GET,
        // single-prompt) protocol — route there instead of assuming every base URL is OpenAI-style.
        if (isPollinationsUrl(cfg.baseUrl)) {
            emit(PollinationsClient.complete(cfg.model, messages))
            return@flow
        }

        if (cfg.apiKey.isBlank()) {
            throw LlmException(role, "No API key set for $role. Add one in Settings.")
        }
        if (cfg.model.isBlank()) {
            throw LlmException(role, "No model set for $role. Add one in Settings.")
        }

        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val bodyStr = buildBody(cfg, messages, temperature, maxTokens, jsonMode).toString()

        var emittedAny = false
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Accept", "text/event-stream")
                .post(bodyStr.toRequestBody(JSON))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errText = resp.body?.string().orEmpty()
                        throw LlmException(role, "$role request failed (HTTP ${resp.code}): ${errText.take(300)}")
                    }
                    val source = resp.body?.source() ?: throw LlmException(role, "$role returned an empty response.")

                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break

                        val delta = try {
                            JSONObject(payload)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content")
                                .orEmpty()
                        } catch (e: Exception) {
                            // A malformed keep-alive or partial frame is not fatal — keep reading.
                            ""
                        }
                        if (delta.isNotEmpty()) {
                            emit(delta)
                            emittedAny = true
                        }
                    }
                }
                break
            } catch (e: LlmException) {
                throw e
            } catch (e: java.io.IOException) {
                // Only retry a completely clean slate: nothing streamed yet, one retry, and a
                // genuinely transient-looking error — never on a real HTTP/API failure, and never
                // after content has already reached the caller (retrying then would duplicate it).
                if (!emittedAny && attempt < 2 && isRetryableNetworkError(e)) continue
                throw LlmException(
                    role,
                    "$role: connection dropped (${e.javaClass.simpleName}${if (attempt > 1) ", retried once" else ""}) — ${e.message ?: "network error"}. Usually a flaky mobile connection; try again.",
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Result of a real, minimal round-trip to a provider — used by the Settings health check. */
    data class PingResult(val ok: Boolean, val latencyMs: Long, val message: String)

    /** Sends the smallest real request that still proves the key/model/base-url actually work. */
    suspend fun ping(cfg: ProviderConfig, role: String): PingResult {
        val started = System.currentTimeMillis()
        return try {
            complete(
                cfg, role,
                listOf(ChatTurn("user", "Reply with exactly one word: hi")),
                temperature = 0.0, maxTokens = 8,
            )
            PingResult(true, System.currentTimeMillis() - started, "OK")
        } catch (e: LlmException) {
            PingResult(false, System.currentTimeMillis() - started, e.message ?: "Failed")
        } catch (e: Exception) {
            PingResult(false, System.currentTimeMillis() - started, e.message ?: "Failed")
        }
    }

    /** Runs the same streaming call but hands back the finished text. */
    suspend fun complete(
        cfg: ProviderConfig,
        role: String,
        messages: List<ChatTurn>,
        temperature: Double = 0.4,
        maxTokens: Int = 8192,
        jsonMode: Boolean = false,
    ): String {
        val sb = StringBuilder()
        stream(cfg, role, messages, temperature, maxTokens, jsonMode).collect { sb.append(it) }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw LlmException(role, "$role returned an empty response.")
        return out
    }
}
