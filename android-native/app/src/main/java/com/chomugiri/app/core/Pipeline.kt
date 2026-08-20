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

/**
 * Kimi (coder) -> GLM (auditor) loop -> DeepSeek R1 on a stuck bug -> Nemotron safety pass.
 * Each stage is a real, sequential model call; nothing here is simulated, which is also why a
 * full run genuinely takes minutes rather than seconds.
 */
fun runPipeline(
    prompt: String,
    settings: AppSettings,
): Flow<PipelineEvent> = flow {
    var files: List<GeneratedFile> = emptyList()
    // 0 means the LITE tier: Kimi's raw output only, no audit/fallback/safety pass at all.
    val maxLoops = settings.maxAuditLoops.coerceIn(0, 8)

    try {
        emit(PipelineEvent.Step("Kimi K3 is writing the code..."))
        val kimiOut = LlmClient.complete(
            settings.provider(RoleKey.KIMI), "Kimi K3",
            listOf(ChatTurn("system", KIMI_SYSTEM_PROMPT), ChatTurn("user", prompt)),
            maxTokens = 8192,
        )
        files = parseFileBlocks(kimiOut)
        if (files.isEmpty()) {
            emit(PipelineEvent.Failed("Kimi K3 didn't return a valid ### FILE: block — try a different model for the coder role in Settings."))
            return@flow
        }
        emit(PipelineEvent.Files(files))
        emit(PipelineEvent.Step("Generated ${files.size} file(s).", done = true))

        var clean = false
        var lastIssues: List<AuditIssue> = emptyList()

        for (i in 1..maxLoops) {
            emit(PipelineEvent.Step("GLM 5.2 audit round $i/$maxLoops..."))
            val glmOut = LlmClient.complete(
                settings.provider(RoleKey.GLM), "GLM 5.2",
                listOf(ChatTurn("system", GLM_AUDIT_SYSTEM_PROMPT), ChatTurn("user", filesToPromptBlock(files))),
                temperature = 0.1, jsonMode = true,
            )
            val parsed = parseIssues(extractJsonObject(glmOut))
            clean = parsed.first
            lastIssues = parsed.second
            if (lastIssues.isEmpty() && !clean) {
                lastIssues = listOf(AuditIssue("unknown", glmOut.take(500)))
            }

            emit(
                PipelineEvent.Step(
                    if (clean) "GLM: code is clean." else "GLM: found ${lastIssues.size} issue(s).",
                    done = true,
                )
            )

            if (clean || i == maxLoops) break

            emit(PipelineEvent.Step("Kimi K3 is fixing issues (round $i)..."))
            val fixOut = LlmClient.complete(
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
            val fixed = parseFileBlocks(fixOut)
            if (fixed.isNotEmpty()) {
                files = mergeFiles(files, fixed)
                emit(PipelineEvent.Files(files))
            }
            emit(PipelineEvent.Step("Fix applied.", done = true))
        }

        if (!clean && lastIssues.isNotEmpty()) {
            emit(PipelineEvent.Step("Kimi/GLM got stuck — DeepSeek R1 is reasoning through the bug..."))
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
            }
            emit(PipelineEvent.Step("DeepSeek R1's fix applied.", done = true))
        }

        if (maxLoops > 0) {
            emit(PipelineEvent.Step("Nemotron 3 Ultra running the final safety check..."))
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
        } else {
            emit(PipelineEvent.Step("LITE tier — skipping audit and safety passes for speed.", done = true))
        }

        emit(PipelineEvent.Done(files))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Unexpected error in the pipeline."))
    }
}
