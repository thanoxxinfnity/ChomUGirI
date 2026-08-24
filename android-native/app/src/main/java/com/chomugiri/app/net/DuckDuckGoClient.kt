package com.chomugiri.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Free web search with no API key, the way the reference implementation does it: DuckDuckGo's
 * plain HTML results page (no JS, no auth) for search, then a real fetch-and-extract of each
 * result page's readable text — not just a snippet, so the model actually has page content
 * (prices, specifics, whatever's on the page) to work from.
 */
object DuckDuckGoClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // DDG's html endpoint serves plain result markup to a normal browser UA; a missing/odd UA
    // gets a degraded or blocked response.
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    // DDG's html endpoint flags rapid successive requests from the same source with a
    // JS-challenge "anomaly" page instead of results - genuinely observed in testing, not a
    // hypothetical. A minimum gap between requests, plus one retry after a longer wait, is a
    // real fix for it; silently returning zero results on a block would look like "nothing
    // found" when the truth is "got rate-limited."
    private var lastRequestAt = 0L
    private const val MIN_GAP_MS = 1_600L

    private suspend fun throttle() {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    suspend fun search(query: String, maxResults: Int = 5): List<SearchHit> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val backoffMs = listOf(3_000L, 6_000L)
        repeat(3) { attempt ->
            throttle()
            try {
                val hits = searchOnce(query, maxResults)
                if (hits.isNotEmpty()) return@withContext hits
                lastError = SearchException("DuckDuckGo returned a challenge page instead of results (rate-limited).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            backoffMs.getOrNull(attempt)?.let { delay(it) }
        }
        throw lastError ?: SearchException("DuckDuckGo search returned nothing.")
    }

    private fun searchOnce(query: String, maxResults: Int): List<SearchHit> {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).addHeader("User-Agent", UA).build()

        val doc: Document = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw SearchException("DuckDuckGo search failed (HTTP ${resp.code}).")
            Jsoup.parse(resp.body?.string().orEmpty(), url)
        }

        return doc.select("div.result, div.web-result").asSequence().mapNotNull { el ->
            val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = a.text().trim()
            val rawHref = a.attr("href")
            val realUrl = unwrapDdgRedirect(rawHref)
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            if (title.isBlank() || realUrl.isBlank()) null else SearchHit(title, realUrl, snippet)
        }.distinctBy { it.url }.take(maxResults).toList()
    }

    /** DDG's html results link through /l/?uddg=<encoded-real-url>&... — unwrap it. */
    private fun unwrapDdgRedirect(href: String): String {
        if (!href.contains("uddg=")) return href
        return try {
            val encoded = href.substringAfter("uddg=").substringBefore("&")
            URLDecoder.decode(encoded, "UTF-8")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            href
        }
    }

    /**
     * Fetches a page and pulls its readable text: drops script/style/nav/footer/header/aside,
     * then returns the remaining visible text. Not as good as a trained extractor, but a real
     * extraction of what's actually on the page rather than a search snippet.
     */
    suspend fun fetchPageText(url: String, maxChars: Int = 3000): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).addHeader("User-Agent", UA).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val contentType = resp.header("Content-Type").orEmpty()
                if (!contentType.contains("html", ignoreCase = true) && contentType.isNotBlank()) return@withContext null
                val doc = Jsoup.parse(resp.body?.string().orEmpty(), url)
                doc.select("script, style, nav, footer, header, aside, noscript").remove()
                val text = doc.body()?.text()?.trim().orEmpty()
                if (text.isBlank()) null else text.take(maxChars)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
