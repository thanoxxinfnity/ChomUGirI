package com.chomugiri.app.net

import com.chomugiri.app.core.ChatTurn
import com.chomugiri.app.core.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        // Deliberately not huge: a mobile TCP handshake that hasn't completed in 20s is not
        // going to, and burning 30s per try before a retry just makes a flaky network feel dead.
        // Recovery comes from retrying on a fresh connection (below), not from waiting longer.
        .connectTimeout(20, TimeUnit.SECONDS)
        // Governs the gap between successive bytes on the socket, not the whole call — a slow
        // model keeps this alive by trickling tokens. It used to be 300s, which meant a provider
        // that accepted the connection and then sent literally nothing (observed live on NIM
        // during a Kimi K3 call — reproduced by a user report, not assumed) left the UI frozen on
        // "reading your request..." for up to 5 minutes with zero feedback and zero retry attempt
        // before finally erroring. 90s is still generous next to every real first-token latency
        // seen from NIM/OpenRouter, and a genuine silence past it now becomes a SocketTimeoutException
        // — retried automatically (see isRetryableNetworkError) instead of a single long dead wait.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Same client, more patience, for calls that legitimately go quiet for a long time before the
     * first token.
     *
     * A model has to read the entire prompt before it emits anything, and the coder's prompt now
     * carries the whole existing project when editing one — so its time-to-first-token grew
     * substantially with that feature, and 90s of silence stopped being clear evidence of a dead
     * connection for that one role. Short calls keep the tighter bound, where a long silence
     * really does mean something is wrong.
     *
     * newBuilder() shares the connection pool and dispatcher, so this is not a second client's
     * worth of sockets — just a different timeout on the same machinery.
     */
    private val patientHttp = http.newBuilder()
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    /** Big generations are the patient ones; a router or a title call is not. */
    private fun clientFor(maxTokens: Int) = if (maxTokens >= 8192) patientHttp else http

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
        // DNS resolution dies transiently whenever the radio hands over between towers or
        // flips wifi/mobile; the host is fine, the lookup just happened at the wrong moment.
        is java.net.UnknownHostException -> true
        else -> false
    }

    /**
     * Attempts per request, and how long to wait before each retry. Backoff is the whole point:
     * the previous version retried instantly, which on a flaky mobile link means the second try
     * hits the exact same dead radio state the first one did and fails identically. Pausing lets
     * a tower handover or a wifi/mobile flip actually finish before trying again.
     */
    /**
     * Statuses that mean "the fleet is busy, come back", not "your request is wrong".
     *
     * Measured against NVIDIA NIM directly: firing 16 concurrent requests at
     * nemotron-3-ultra-550b returned 429 Too Many Requests and 503 "Service temporarily
     * overloaded" for 9 of them. These were being thrown straight to the user as a hard failure,
     * so a build could die outright just because NVIDIA's GPU pool happened to be full for a
     * second — the one case where waiting genuinely does fix it.
     */
    private fun isRetryableStatus(code: Int): Boolean =
        code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    /** Honour the server's own Retry-After when it sends one; fall back to our backoff. */
    private fun retryDelayFor(resp: okhttp3.Response, attempt: Int): Long {
        val header = resp.header("Retry-After")?.trim()?.toLongOrNull()
        val ours = RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
        // Cap it: a provider asking us to sit for minutes is worse than failing with a clear error.
        return if (header != null) (header * 1000).coerceIn(1_000, 15_000) else ours
    }

    private const val MAX_ATTEMPTS = 4
    private val RETRY_BACKOFF_MS = longArrayOf(1_000, 3_000, 7_000)

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
                val retryIn: Long? = clientFor(maxTokens).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errText = resp.body?.string().orEmpty()
                        // NIM's own model routing turned out to be flaky, not just its capacity —
                        // caught live: moonshotai/kimi-k3 answered 200/200/200/429/404/200 across
                        // six calls a second apart, no change on our end between them. The tell is
                        // the body: a model that genuinely doesn't exist there answers with real
                        // text ("404 page not found" for one that was never real, a JSON `detail`
                        // for one that was retired) — this transient case comes back with nothing
                        // in the body at all. So an empty-body 404 is treated as noise to retry
                        // through, same as a 429; only a 404 that actually says something is taken
                        // as the model really not being there.
                        val transientNotFound = resp.code == 404 && errText.isBlank()
                        if (resp.code == 404 && !transientNotFound) {
                            throw LlmException(
                                role,
                                "$role: the model \"${cfg.model}\" doesn't exist on ${cfg.baseUrl.removePrefix("https://").substringBefore('/')}. " +
                                    "Pick a different model for this role in Settings.",
                            )
                        }
                        // Busy, not broken: back off and try again rather than killing the run.
                        if ((isRetryableStatus(resp.code) || transientNotFound) && !emittedAny && attempt < MAX_ATTEMPTS) {
                            return@use retryDelayFor(resp, attempt)
                        }
                        if (isRetryableStatus(resp.code) || transientNotFound) {
                            throw LlmException(
                                role,
                                "$role: the provider is overloaded right now (HTTP ${resp.code}) and " +
                                    "kept refusing after $attempt attempts. Try again in a moment, or " +
                                    "pick a lighter tier.",
                            )
                        }
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
                    null
                }
                if (retryIn != null) {
                    delay(retryIn)
                    continue
                }
                break
            } catch (e: LlmException) {
                throw e
            } catch (e: java.io.IOException) {
                // Only retry a completely clean slate: nothing streamed yet, attempts left, and
                // a genuinely transient-looking error — never on a real HTTP/API failure, and
                // never after content has already reached the caller (that would duplicate it).
                if (!emittedAny && attempt < MAX_ATTEMPTS && isRetryableNetworkError(e)) {
                    delay(RETRY_BACKOFF_MS[(attempt - 1).coerceAtMost(RETRY_BACKOFF_MS.lastIndex)])
                    continue
                }
                val tries = if (attempt > 1) ", $attempt attempts with backoff" else ""
                throw LlmException(
                    role,
                    "$role: connection dropped (${e.javaClass.simpleName}$tries) — ${e.message ?: "network error"}. Your connection couldn't reach the provider; check signal and try again.",
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Result of a real, minimal round-trip to a provider — used by the Settings health check. */
    data class PingResult(val ok: Boolean, val latencyMs: Long, val message: String)

    /**
     * Sends the smallest real request that still proves the key/model/base-url actually work.
     *
     * The budget is deliberately not tiny. Reasoning models — Kimi K3 and the Nemotron family
     * among them — spend tokens thinking before they emit a single visible character, and that
     * thinking is billed against max_tokens. Verified live: Kimi K3 asked for one word returns
     * content=null with finish_reason=length at max_tokens=8, and a clean "hi" at 400. A health
     * check that reports a perfectly good key as broken is worse than a slightly costlier one.
     */
    suspend fun ping(cfg: ProviderConfig, role: String): PingResult {
        val started = System.currentTimeMillis()
        return try {
            complete(
                cfg, role,
                listOf(ChatTurn("user", "Reply with exactly one word: hi")),
                temperature = 0.0, maxTokens = 512,
            )
            PingResult(true, System.currentTimeMillis() - started, "OK")
        } catch (e: LlmException) {
            PingResult(false, System.currentTimeMillis() - started, e.message ?: "Failed")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
