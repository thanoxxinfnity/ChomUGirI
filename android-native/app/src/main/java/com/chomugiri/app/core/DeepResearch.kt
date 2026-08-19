package com.chomugiri.app.core

import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.SearchClient
import com.chomugiri.app.net.SearchHit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Plan -> search -> synthesise. The answer is written only from results that were genuinely
 * fetched, and every claim is asked to carry a [n] citation back to a real URL. If no search key
 * is configured this refuses outright rather than letting the model improvise from memory.
 */
fun runDeepResearch(
    question: String,
    settings: AppSettings,
): Flow<PipelineEvent> = flow {
    if (!SearchClient.isConfigured(settings.searchProvider, settings.searchApiKey)) {
        emit(
            PipelineEvent.Failed(
                "Deep Research needs a web search API key (Tavily, Brave, or Serper) — add one in " +
                    "Settings. Without real search results this would just be the model guessing " +
                    "from memory, which isn't research."
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
        val sourceBlock = sources.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}\nURL: ${h.url}\n${h.snippet}"
        }.joinToString("\n\n")

        emit(PipelineEvent.Step("Reading ${sources.size} sources and writing the answer..."))

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
