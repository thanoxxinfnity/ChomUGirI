package com.chomugiri.app.net

import com.chomugiri.app.core.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The base URL a role's provider config must contain to be routed here instead of LlmClient's OpenAI-style path. */
const val POLLINATIONS_BASE_URL = "https://text.pollinations.ai"
const val POLLINATIONS_DEFAULT_MODEL = "openai-fast"

fun isPollinationsUrl(baseUrl: String): Boolean = baseUrl.contains("pollinations.ai", ignoreCase = true)

/**
 * Pollinations' free, keyless text API — verified live against the real endpoint while building
 * this: it is a single GET request with the prompt in the URL path, not the OpenAI chat/completions
 * shape the rest of this app assumes. Two things confirmed by hitting it directly:
 *  - Only "openai-fast" is actually available on the anonymous/no-key tier right now — a made-up
 *    or paid-tier model name (e.g. "claude-hybrid") 404s.
 *  - Adding a `system=` query param pushes the request onto a billed path (402), even with no
 *    key — so system instructions are folded into the prompt text itself instead, to stay free.
 *
 * Real limitation: the whole prompt (system + history + user turn) rides in the URL path, so a
 * very long system prompt — exactly what the KIMI/GLM/DEEPSEEK/NEMOTRON roles use — can hit a
 * server's URL-length limit and fail. This is genuinely fine for the FAST (chat) role or a
 * per-message override; treat it as unreliable for the coding roles' long prompts.
 */
object PollinationsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun buildPrompt(messages: List<ChatTurn>): String = buildString {
        messages.forEach { turn ->
            when (turn.role) {
                "system" -> append("Instructions: ${turn.content}\n\n")
                "user" -> append("User: ${turn.content}\n\n")
                else -> append("Assistant: ${turn.content}\n\n")
            }
        }
        append("Assistant:")
    }

    suspend fun complete(model: String, messages: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val safeModel = model.ifBlank { POLLINATIONS_DEFAULT_MODEL }
        val encodedPrompt = URLEncoder.encode(buildPrompt(messages), "UTF-8").replace("+", "%20")
        val req = Request.Builder()
            .url("$POLLINATIONS_BASE_URL/$encodedPrompt?model=$safeModel")
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmException("Pollinations", "Pollinations request failed (HTTP ${resp.code}): ${text.take(300)}")
            }
            if (text.isBlank()) throw LlmException("Pollinations", "Pollinations returned an empty response.")
            text.trim()
        }
    }
}
