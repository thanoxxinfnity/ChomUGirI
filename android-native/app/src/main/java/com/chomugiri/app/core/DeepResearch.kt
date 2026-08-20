package com.chomugiri.app.core

import com.chomugiri.app.net.DuckDuckGoClient
import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.SearchClient
import com.chomugiri.app.net.SearchHit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How many top results get their full page fetched and read, not just the search snippet. */
private const val PAGES_TO_READ = 4

/**
 * Plan -> search -> fetch the actual pages -> synthesise. The answer is written only from
 * content that was genuinely retrieved, and every claim is asked to carry a [n] citation back to
 * a real URL. Search defaults to DuckDuckGo (no key needed); if search genuinely fails this
 * refuses outright rather than letting the model improvise from memory.
 */
fun runDeepResearch(
    question: String,
    settings: AppSettings,
): Flow<PipelineEvent> = flow {
    if (!SearchClient.isConfigured(settings.searchProvider, settings.searchApiKey)) {
        emit(
            PipelineEvent.Failed(
                "Deep Research needs a search key for ${settings.searchProvider} — add one in " +
                    "Settings, or switch the provider to DuckDuckGo (no key needed)."
            )
        )
        return@flow
    }

    try {
        emit(PipelineEvent.Step("Planning search queries..."))
        val planRaw = LlmClient.complete(
            settings.provider(RoleKey.DEEPSEEK), "Research planner",
            listOf(ChatTurn("system", RESEARCH_PLAN_PROMPT), ChatTurn("user", question)),
            temperature = 0.3, jsonMode = true, maxTokens = 1024,
        )
        val queries = buildList {
            extractJsonObject(planRaw)?.optJSONArray("queries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val q = arr.optString(i).trim()
                    if (q.isNotEmpty()) add(q)
                }
            }
            if (isEmpty()) add(question) // planner failed — still do one honest search
        }.take(5)

        emit(PipelineEvent.Step("Planned ${queries.size} search(es).", done = true))

        val hits = LinkedHashMap<String, SearchHit>()
        for ((i, q) in queries.withIndex()) {
            emit(PipelineEvent.Step("Searching ${i + 1}/${queries.size}: $q"))
            val found = try {
                SearchClient.search(settings.searchProvider, settings.searchApiKey, q)
            } catch (e: Exception) {
                emit(PipelineEvent.Step("Search failed: ${e.message}", done = true))
                emptyList()
            }
            found.forEach { if (it.url.isNotBlank()) hits.putIfAbsent(it.url, it) }
            emit(PipelineEvent.Step("Found ${found.size} result(s).", done = true))
        }

        if (hits.isEmpty()) {
            emit(PipelineEvent.Failed("No search results came back, so there is nothing to base an answer on."))
            return@flow
        }

        val sources = hits.values.toList()

        // Pull the real page text for the top results instead of relying only on the snippet —
        // this is what actually lets the model quote a price or a specific figure off the page.
        val pageText = HashMap<String, String>()
        sources.take(PAGES_TO_READ).forEach { hit ->
            emit(PipelineEvent.Step("Reading ${hit.url}...", done = false))
            val text = try {
                DuckDuckGoClient.fetchPageText(hit.url)
            } catch (e: Exception) {
                null
            }
            if (text != null) pageText[hit.url] = text
            emit(PipelineEvent.Step(if (text != null) "Read ${hit.url}" else "Could not read ${hit.url}", done = true))
        }

        val sourceBlock = sources.mapIndexed { i, h ->
            val body = pageText[h.url] ?: h.snippet
            "[${i + 1}] ${h.title}\nURL: ${h.url}\n$body"
        }.joinToString("\n\n")

        emit(PipelineEvent.Step("Writing the answer from ${sources.size} sources (${pageText.size} full pages read)...", done = true))

        val answer = StringBuilder()
        LlmClient.stream(
            settings.provider(RoleKey.DEEPSEEK), "Research analyst",
            listOf(
                ChatTurn("system", RESEARCH_SYNTH_PROMPT),
                ChatTurn("user", "Question: $question\n\nSources:\n$sourceBlock"),
            ),
            temperature = 0.3, maxTokens = 4096,
        ).collect {
            answer.append(it)
            emit(PipelineEvent.Chunk(it))
        }

        emit(PipelineEvent.Step("Answer written from ${sources.size} sources.", done = true))

        // Append the real source list so every citation is checkable.
        val refs = buildString {
            append("\n\n---\n**Sources**\n")
            sources.forEachIndexed { i, h -> append("[${i + 1}] ${h.title} — ${h.url}\n") }
        }
        emit(PipelineEvent.Chunk(refs))
        emit(PipelineEvent.Done(emptyList()))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Deep Research failed."))
    }
}
