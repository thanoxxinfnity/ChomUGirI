package com.chomugiri.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchHit(val title: String, val url: String, val snippet: String)

class SearchException(message: String) : Exception(message)

/**
 * Web search for Deep Research. There is deliberately no built-in/default key and no fallback
 * to "let the model answer from memory" — without a real search key Deep Research is simply
 * unavailable, because an LLM recalling facts is not research.
 */
object SearchClient {

    val PROVIDERS = listOf("tavily", "brave", "serper")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(provider: String, key: String) = key.isNotBlank() && provider in PROVIDERS

    suspend fun search(provider: String, apiKey: String, query: String, limit: Int = 5): List<SearchHit> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw SearchException("No search API key set — add one in Settings to use Deep Research.")
            when (provider) {
                "tavily" -> tavily(apiKey, query, limit)
                "brave" -> brave(apiKey, query, limit)
                "serper" -> serper(apiKey, query, limit)
                else -> throw SearchException("Unknown search provider: $provider")
            }
        }

    private fun exec(req: Request, who: String): JSONObject {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw SearchException("$who search failed (HTTP ${resp.code}): ${text.take(200)}")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw SearchException("$who returned a response that wasn't JSON.")
            }
        }
    }

    private fun tavily(key: String, query: String, limit: Int): List<SearchHit> {
        val body = JSONObject()
            .put("api_key", key)
            .put("query", query)
            .put("max_results", limit)
            .put("search_depth", "advanced")
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val arr = exec(req, "Tavily").optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SearchHit(it.optString("title"), it.optString("url"), it.optString("content"))
            }
        }
    }

    private fun brave(key: String, query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=$q&count=$limit")
            .addHeader("Accept", "application/json")
            .addHeader("X-Subscription-Token", key)
            .get()
            .build()
        val arr = exec(req, "Brave").optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SearchHit(it.optString("title"), it.optString("url"), it.optString("description"))
            }
        }
    }

    private fun serper(key: String, query: String, limit: Int): List<SearchHit> {
        val body = JSONObject().put("q", query).put("num", limit)
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .addHeader("X-API-KEY", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val arr = exec(req, "Serper").optJSONArray("organic") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                SearchHit(it.optString("title"), it.optString("link"), it.optString("snippet"))
            }
        }
    }
}
