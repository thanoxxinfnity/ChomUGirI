package com.chomugiri.app.core

import org.json.JSONObject

private val FILE_BLOCK = Regex(
    "###\\s*FILE:\\s*(.+?)\\s*\\n```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```",
)

/** Parses the "### FILE: path\n```lang\n...\n```" convention the coder models are prompted to emit. */
fun parseFileBlocks(text: String): List<GeneratedFile> =
    FILE_BLOCK.findAll(text).mapNotNull { m ->
        val path = m.groupValues[1].trim().trimStart('/')
        val content = m.groupValues[2].removeSuffix("\n")
        if (path.isEmpty()) null else GeneratedFile(path, content)
    }.toList()

fun filesToPromptBlock(files: List<GeneratedFile>): String =
    files.joinToString("\n\n") { "### FILE: ${it.path}\n```\n${it.content}\n```" }

fun mergeFiles(base: List<GeneratedFile>, updates: List<GeneratedFile>): List<GeneratedFile> {
    val map = LinkedHashMap<String, GeneratedFile>()
    base.forEach { map[it.path] = it }
    updates.forEach { map[it.path] = it }
    return map.values.toList()
}

/** Best-effort JSON extraction from a response that may wrap its JSON in prose or fences. */
fun extractJsonObject(text: String): JSONObject? {
    val fenced = Regex("```json\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        ?: Regex("```\\s*([\\s\\S]*?)```").find(text)
    val candidate = fenced?.groupValues?.get(1) ?: text
    val start = candidate.indexOf('{')
    val end = candidate.lastIndexOf('}')
    if (start == -1 || end == -1 || end < start) return null
    return try {
        JSONObject(candidate.substring(start, end + 1))
    } catch (e: Exception) {
        null
    }
}
