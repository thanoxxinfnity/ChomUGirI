package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.LlmClient
import com.chomugiri.app.net.TerminalClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Commands we refuse to send no matter what the model asks for. */
private val BLOCKED = listOf(
    Regex("""\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+/(\s|$)"""),
    Regex("""\bmkfs(\.|\s)"""),
    Regex("""\bdd\s+.*of=/dev/"""),
    Regex("""\b(shutdown|reboot|halt|poweroff)\b"""),
    Regex(""":\(\)\s*\{.*\|\s*:\s*&"""), // fork bomb
    Regex("""\bchmod\s+-R\s+777\s+/(\s|$)"""),
)

fun isBlockedCommand(cmd: String): Boolean = BLOCKED.any { it.containsMatchIn(cmd) }

private fun b64(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

/**
 * Copies the generated project onto the user's own machine through their terminal. Content goes
 * over base64 so quoting, newlines and unicode survive the PTY intact.
 */
fun pushFilesScript(files: List<GeneratedFile>, dir: String): List<Pair<String, String>> =
    files.map { f ->
        val target = "$dir/${f.path}"
        val parent = target.substringBeforeLast('/', dir)
        f.path to "mkdir -p '$parent' && echo '${b64(f.content)}' | base64 -d > '$target'"
    }

/**
 * Lets the model drive the user's real shell, one command at a time, reading the real output of
 * each before choosing the next. Nothing is simulated: every command runs on their machine, and
 * a failure is fed back verbatim so the model has to actually deal with it.
 */
fun runTerminalAgent(
    goal: String,
    settings: AppSettings,
    files: List<GeneratedFile> = emptyList(),
    workDir: String = "~/chomugiri-build",
    maxSteps: Int = 25,
): Flow<PipelineEvent> = flow {
    if (!TerminalClient.connected.value) {
        emit(PipelineEvent.Failed("Terminal is not connected. Connect it on the Terminal tab first."))
        return@flow
    }
    if (!settings.agentTerminalEnabled) {
        emit(PipelineEvent.Failed("Agent terminal access is off. Turn it on in Settings to let ChomuGirI run commands."))
        return@flow
    }

    try {
        emit(PipelineEvent.Step("Preparing $workDir on your machine..."))
        val mk = TerminalClient.runCommand("mkdir -p $workDir && cd $workDir && pwd", 60_000)
        val resolvedDir = mk.output.lines().lastOrNull { it.trim().startsWith("/") }?.trim() ?: workDir
        emit(PipelineEvent.Step("Working directory: $resolvedDir", done = true))

        if (files.isNotEmpty()) {
            emit(PipelineEvent.Step("Copying ${files.size} file(s) over..."))
            for ((path, cmd) in pushFilesScript(files, resolvedDir)) {
                val r = TerminalClient.runCommand(cmd, 120_000)
                if (r.exitCode != 0) {
                    emit(PipelineEvent.Failed("Failed to write $path: ${r.output.take(400)}"))
                    return@flow
                }
            }
            emit(PipelineEvent.Step("Copied ${files.size} file(s) to $resolvedDir.", done = true))
        }

        val history = mutableListOf(
            ChatTurn("system", TERMINAL_AGENT_PROMPT),
            ChatTurn(
                "user",
                buildString {
                    append("Goal: $goal\n\n")
                    append("You are already in a shell. Working directory: $resolvedDir\n")
                    if (files.isNotEmpty()) {
                        append("These files have already been written there:\n")
                        files.forEach { append("- ${it.path}\n") }
                    }
                    append("\nGive me the first command.")
                },
            ),
        )

        for (step in 1..maxSteps) {
            val raw = LlmClient.complete(
                settings.provider(RoleKey.KIMI), "Build agent",
                history, temperature = 0.2, jsonMode = true, maxTokens = 1024,
            )
            val obj = extractJsonObject(raw)
            if (obj == null) {
                emit(PipelineEvent.Failed("The agent returned something that wasn't valid JSON:\n${raw.take(300)}"))
                return@flow
            }

            val thought = obj.optString("thought").trim()
            val command = obj.optString("command").trim()
            val done = obj.optBoolean("done", false)

            if (done || command.isEmpty()) {
                emit(PipelineEvent.Step(if (thought.isNotEmpty()) thought else "Agent finished.", done = true))
                emit(PipelineEvent.Done(emptyList()))
                return@flow
            }

            if (isBlockedCommand(command)) {
                emit(PipelineEvent.Failed("Refused to run a destructive command: $command"))
                return@flow
            }

            emit(PipelineEvent.Step("[$step] ${thought.ifEmpty { command }}"))
            emit(PipelineEvent.Chunk("\n$ $command\n"))

            val result = TerminalClient.runCommand("cd $resolvedDir && $command", 600_000)
            val shown = result.output.take(4000)
            emit(PipelineEvent.Chunk(shown + "\n"))
            emit(
                PipelineEvent.Step(
                    if (result.timedOut) "[$step] timed out"
                    else "[$step] exit ${result.exitCode}",
                    done = true,
                )
            )

            history += ChatTurn("assistant", raw)
            history += ChatTurn(
                "user",
                "exit_code=${result.exitCode}${if (result.timedOut) " (timed out)" else ""}\noutput:\n" +
                    result.output.takeLast(3000) + "\n\nNext command?",
            )
        }

        emit(PipelineEvent.Failed("Agent hit the $maxSteps-step limit without finishing."))
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Terminal agent failed."))
    }
}

/** The concrete goal used by the "Build real APK" action. */
fun apkBuildGoal(projectName: String): String = """
Build a real debug APK from the project in this directory.

The project is a web app (HTML/CSS/JS or a node project). Wrap it with Capacitor and build it:
 1. Check that node, npm and java are available; report clearly if any is missing.
 2. Make sure ANDROID_HOME (or ANDROID_SDK_ROOT) points at an Android SDK; report clearly if it does not.
 3. Put the web files under a 'www' directory if they are not already.
 4. Initialise a Capacitor project named "$projectName" (appId com.chomugiri.generated), add the android platform.
 5. Run ./gradlew assembleDebug inside the android directory.
 6. Print the absolute path of the produced .apk file.

If a required tool or the Android SDK is genuinely missing, stop and say exactly what the user
needs to install — do not pretend the build succeeded.
""".trim()
