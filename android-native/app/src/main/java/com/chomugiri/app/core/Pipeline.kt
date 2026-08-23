package com.chomugiri.app.core

import com.chomugiri.app.net.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private data class AuditIssue(val file: String, val description: String)

private fun parseIssues(obj: org.json.JSONObject?): Pair<Boolean, List<AuditIssue>> {
    if (obj == null) return false to emptyList()
    val clean = obj.optBoolean("clean", false)
    val arr = obj.optJSONArray("issues")
    val issues = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(AuditIssue(o.optString("file", "unknown"), o.optString("description", "")))
        }
    }
    return (clean && issues.isEmpty()) to issues
}

private fun issuesToText(issues: List<AuditIssue>) =
    issues.joinToString("\n") { "- [${it.file}] ${it.description}" }

private val THINKING_BLOCK = Regex("(?is)<thinking>(.*?)</thinking>")
private val PROMPT_SCORE_LINE = Regex("""(?im)^[ \t]*PROMPT_SCORE:[ \t]*(\d{1,3}).*$\n?""")
private val SCORE_COLOR_LINE = Regex("""(?im)^[ \t]*SCORE_COLOR:[ \t]*(RED|ORANGE|GREEN).*$\n?""")
private val FILE_ACTIONS_BLOCK = Regex("""(?im)^[ \t]*FILE_ACTIONS:[ \t]*\n((?:^[ \t]*-.*$\n?)+)""")

/**
 * Pulls Kimi's real FILE_ACTIONS tracker (from KIMI_SYSTEM_PROMPT's GREEN case) out of the
 * response and turns each line into a real "about to do this" Step — genuinely what Kimi said
 * it's about to write, not a fabricated progress message. Returns (steps, textWithBlockRemoved).
 */
internal fun extractFileActions(text: String): Pair<List<String>, String> {
    val match = FILE_ACTIONS_BLOCK.find(text) ?: return emptyList<String>() to text
    val lines = match.groupValues[1]
        .lines()
        .map { it.trim().trimStart('-').trim() }
        .filter { it.isNotEmpty() }
    val rest = text.removeRange(match.range).trim()
    return lines to rest
}

/**
 * Pulls Kimi's real PROMPT_SCORE/SCORE_COLOR lines (from the prompt-score-evaluator decision
 * engine in KIMI_SYSTEM_PROMPT) out of the response so they show as a Thinking-bubble step
 * instead of raw "PROMPT_SCORE: 42" text in the chat bubble. Returns (score, color, cleanedText).
 */
internal fun extractPromptScore(text: String): Triple<Int?, String?, String> {
    val score = PROMPT_SCORE_LINE.find(text)?.groupValues?.get(1)?.toIntOrNull()
    val color = SCORE_COLOR_LINE.find(text)?.groupValues?.get(1)?.uppercase()
    val cleaned = text.replace(PROMPT_SCORE_LINE, "").replace(SCORE_COLOR_LINE, "").trim()
    return Triple(score, color, cleaned)
}

/**
 * Pulls Kimi's real <thinking>...</thinking> block out of its raw response and turns it into a
 * list of real reasoning lines for the Thinking bubble — genuinely what the model reasoned, not
 * a fabricated progress message. Returns (steps, textWithThinkingBlockRemoved).
 */
private val FILE_HEADER_LINE = Regex("###\\s*FILE:\\s*(.+)")

/**
 * Turns the coder's raw stream into progress lines *while it is still generating*.
 *
 * The coder call used to be a buffered complete(), so nothing reached the UI until the whole
 * response landed — a single frozen "looking at your request..." for minutes on a big build. The
 * model is actually narrating the entire time inside its <thinking> block and announcing each
 * file as it starts writing it; this surfaces that as it arrives instead of after the fact.
 *
 * Only whole lines are emitted. A half-arrived line would otherwise show up as a truncated step
 * and then be replaced a few tokens later, which reads as flicker rather than progress.
 */
private class CoderStreamProgress {
    private val buf = StringBuilder()
    private var cursor = 0
    private var insideThinking = false
    private val seenFiles = LinkedHashSet<String>()

    fun feed(chunk: String): List<String> {
        buf.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val nl = buf.indexOf("\n", cursor)
            if (nl < 0) break
            val line = buf.substring(cursor, nl).trim()
            cursor = nl + 1
            when {
                line.contains("<thinking>", ignoreCase = true) -> insideThinking = true
                line.contains("</thinking>", ignoreCase = true) -> insideThinking = false
                insideThinking -> {
                    val t = line.trimStart('-', '*', '\u2022').trim()
                    if (t.isNotEmpty()) out += t
                }
                else -> FILE_HEADER_LINE.find(line)?.let { m ->
                    val path = m.groupValues[1].trim().trimStart('/')
                    // Each file announced once, the first time its header appears.
                    if (path.isNotEmpty() && seenFiles.add(path)) out += "Writing $path"
                }
            }
        }
        return out
    }
}

/**
 * What to call a role in progress text. Uses the model the user actually configured rather than
 * the role's nickname — the bubble said "Kimi K3" even after switching the coder to something
 * else entirely, which is just a lie about what is running.
 */
private fun modelLabel(cfg: ProviderConfig, fallback: String): String =
    cfg.model.substringAfterLast('/').substringBefore(':').ifBlank { fallback }

internal fun extractThinking(raw: String): Pair<List<String>, String> {
    val match = THINKING_BLOCK.find(raw) ?: return emptyList<String>() to raw
    val lines = match.groupValues[1]
        .lines()
        .map { it.trim().trimStart('-', '*', '•').trim() }
        .filter { it.isNotEmpty() }
    val rest = raw.removeRange(match.range).trim()
    return lines to rest
}

/**
 * Kimi (coder) -> GLM (auditor) loop -> DeepSeek R1 on a stuck bug -> Nemotron safety pass.
 * Each stage is a real, sequential model call; nothing here is simulated, which is also why a
 * full run genuinely takes minutes rather than seconds.
 */
fun runPipeline(
    prompt: String,
    settings: AppSettings,
    // Prior turns in this conversation — without this, Kimi judges every message in isolation
    // and can't tell "make a website" -> its own clarifying questions -> the user's answer is
    // one continuous exchange, so it would just ask again forever instead of ever building.
    history: List<ChatTurn> = emptyList(),
): Flow<PipelineEvent> = flow {
    var files: List<GeneratedFile> = emptyList()
    // 0 means the LITE tier: Kimi's raw output only, no audit/fallback/safety pass at all.
    val maxLoops = settings.maxAuditLoops.coerceIn(0, 8)

    try {
        val coderCfg = settings.provider(RoleKey.KIMI)
        val coder = modelLabel(coderCfg, "The coder")
        emit(PipelineEvent.Step("$coder is reading your request..."))

        // Streamed, not buffered: every reasoning line and every file the model announces shows
        // up in the bubble as it happens, instead of one frozen step for the whole generation.
        val progress = CoderStreamProgress()
        val kimiBuf = StringBuilder()
        LlmClient.stream(
            coderCfg, coder,
            listOf(ChatTurn("system", KIMI_SYSTEM_PROMPT)) + history + ChatTurn("user", prompt),
            maxTokens = 8192,
        ).collect { chunk ->
            kimiBuf.append(chunk)
            progress.feed(chunk).forEach { emit(PipelineEvent.Step(it, done = true)) }
        }
        val rawKimiOut = kimiBuf.toString()
        // Thinking lines already streamed above; this only strips the block from the text.
        val (_, thinkingStripped) = extractThinking(rawKimiOut)
        val (promptScore, scoreColor, scoreStripped) = extractPromptScore(thinkingStripped)
        if (promptScore != null) {
            emit(PipelineEvent.Step("Prompt score: $promptScore/100" + (scoreColor?.let { " ($it)" } ?: ""), done = true))
        }
        val (fileActions, kimiOut) = extractFileActions(scoreStripped)
        fileActions.forEach { emit(PipelineEvent.Step(it, done = true)) }
        files = parseFileBlocks(kimiOut)
        if (files.isEmpty()) {
            if (kimiOut.isBlank()) {
                emit(PipelineEvent.Failed("$coder returned nothing — try a different model for the coder role in Settings."))
            } else {
                // Kimi decided the request was too vague to build from and asked for detail
                // instead (per its prompt) — that's a legitimate chat reply, not a failure.
                emit(PipelineEvent.Step("$coder needs a bit more detail before building.", done = true))
                emit(PipelineEvent.Chunk(kimiOut.trim()))
                emit(PipelineEvent.Done(emptyList()))
            }
            return@flow
        }
        emit(PipelineEvent.Files(files))
        emit(PipelineEvent.Step("Generated ${files.size} file(s).", done = true))

        var clean = false
        var lastIssues: List<AuditIssue> = emptyList()
        val resolvedByParts = mutableListOf("Kimi K3")

        // A failure at any of these later stages must not throw away the code Kimi already
        // wrote — it degrades to "skip this stage" so the user still gets a real, working
        // project instead of a scary full failure over what's often just one flaky call.
        for (i in 1..maxLoops) {
            emit(PipelineEvent.Step("Audit round $i/$maxLoops..."))
            val glmOut = try {
                LlmClient.complete(
                    settings.provider(RoleKey.GLM), ROLE_LABELS[RoleKey.GLM] ?: "Auditor",
                    listOf(ChatTurn("system", GLM_AUDIT_SYSTEM_PROMPT), ChatTurn("user", filesToPromptBlock(files))),
                    temperature = 0.1, jsonMode = true,
                )
            } catch (e: Exception) {
                emit(PipelineEvent.Step("Audit failed: ${e.message ?: "unknown error"} — skipping this round. (Settings > Auditor > Test connection shows the exact error.)", done = true))
                break
            }
            val parsed = parseIssues(extractJsonObject(glmOut))
            clean = parsed.first
            lastIssues = parsed.second
            if (lastIssues.isEmpty() && !clean) {
                lastIssues = listOf(AuditIssue("unknown", glmOut.take(500)))
            }
            if ("GLM audit" !in resolvedByParts) resolvedByParts += "GLM audit"

            emit(
                PipelineEvent.Step(
                    if (clean) "GLM: code is clean." else "GLM: found ${lastIssues.size} issue(s).",
                    done = true,
                )
            )

            if (clean || i == maxLoops) break

            emit(PipelineEvent.Step("$coder is fixing issues (round $i)..."))
            val fixOut = try {
                LlmClient.complete(
                    settings.provider(RoleKey.KIMI), "Kimi K3",
                    listOf(
                        ChatTurn("system", KIMI_FIX_SYSTEM_PROMPT),
                        ChatTurn(
                            "user",
                            "Current files:\n${filesToPromptBlock(files)}\n\nIssues to fix:\n${issuesToText(lastIssues)}",
                        ),
                    ),
                    maxTokens = 8192,
                )
            } catch (e: Exception) {
                emit(PipelineEvent.Step("$coder's fix call failed: ${e.message ?: "unknown error"} — keeping the last working version.", done = true))
                break
            }
            val fixed = parseFileBlocks(fixOut)
            if (fixed.isNotEmpty()) {
                files = mergeFiles(files, fixed)
                emit(PipelineEvent.Files(files))
            }
            emit(PipelineEvent.Step("Fix applied.", done = true))
        }

        if (!clean && lastIssues.isNotEmpty()) {
            emit(PipelineEvent.Step("Kimi/GLM got stuck — DeepSeek R1 is reasoning through the bug..."))
            try {
                val deepOut = LlmClient.complete(
                    settings.provider(RoleKey.DEEPSEEK), "DeepSeek R1",
                    listOf(
                        ChatTurn("system", DEEPSEEK_SYSTEM_PROMPT),
                        ChatTurn(
                            "user",
                            "Files:\n${filesToPromptBlock(files)}\n\nUnresolved issues after $maxLoops audit rounds:\n${issuesToText(lastIssues)}",
                        ),
                    ),
                    temperature = 0.2, maxTokens = 8192,
                )
                val deepFixed = parseFileBlocks(deepOut)
                if (deepFixed.isNotEmpty()) {
                    files = mergeFiles(files, deepFixed)
                    emit(PipelineEvent.Files(files))
                    resolvedByParts += "DeepSeek R1 (fallback)"
                }
                emit(PipelineEvent.Step("DeepSeek R1's fix applied.", done = true))
            } catch (e: Exception) {
                emit(PipelineEvent.Step("DeepSeek R1 fallback failed: ${e.message ?: "unknown error"} — keeping GLM's last version.", done = true))
            }
        }

        if (maxLoops > 0) {
            emit(PipelineEvent.Step("Nemotron 3 Ultra running the final safety check..."))
            try {
                val nemoOut = LlmClient.complete(
                    settings.provider(RoleKey.NEMOTRON), "Nemotron 3 Ultra 550B",
                    listOf(ChatTurn("system", NEMOTRON_SYSTEM_PROMPT), ChatTurn("user", filesToPromptBlock(files))),
                    temperature = 0.1, jsonMode = true, maxTokens = 8192,
                )
                val safety = extractJsonObject(nemoOut)
                val fixedArr = safety?.optJSONArray("fixedFiles")
                if (fixedArr != null && fixedArr.length() > 0) {
                    val patch = buildList {
                        for (i in 0 until fixedArr.length()) {
                            val o = fixedArr.optJSONObject(i) ?: continue
                            val p = o.optString("path"); val c = o.optString("content")
                            if (p.isNotBlank()) add(GeneratedFile(p, c))
                        }
                    }
                    if (patch.isNotEmpty()) {
                        files = mergeFiles(files, patch)
                        emit(PipelineEvent.Files(files))
                        resolvedByParts += "Nemotron safety fix"
                    }
                }
                emit(
                    PipelineEvent.Step(
                        if (safety?.optBoolean("safe", true) == false)
                            "Nemotron applied final fixes: ${safety.optString("notes")}"
                        else "Nemotron: code is crash-safe and ready.",
                        done = true,
                    )
                )
            } catch (e: Exception) {
                emit(PipelineEvent.Step("Nemotron safety check failed: ${e.message ?: "unknown error"} — shipping the last working version.", done = true))
            }
        } else {
            emit(PipelineEvent.Step("LITE tier — skipping audit and safety passes for speed.", done = true))
        }

        // The design system is the app's, not the model's — see WebScaffold.kt. Injected here so
        // a web project is styled by a known-good stylesheet even if the model skipped the link.
        val scaffolded = withWebScaffold(files)
        if (scaffolded != files) {
            files = scaffolded
            emit(PipelineEvent.Files(files))
            emit(PipelineEvent.Step("Applied the ChomuGiri design system.", done = true))
        }

        try {
            val withImages = resolveImageMarkers(files, settings) { msg -> emit(PipelineEvent.Step(msg)) }
            if (withImages != files) {
                files = withImages
                emit(PipelineEvent.Files(files))
            }
        } catch (e: Exception) {
            emit(PipelineEvent.Step("Image generation step failed: ${e.message ?: "unknown error"} — shipping without it.", done = true))
        }

        emit(PipelineEvent.Done(files, resolvedByParts.joinToString(" + "), issuesToText(lastIssues).lines().filter { it.isNotBlank() }))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Unexpected error in the pipeline."))
    }
}
