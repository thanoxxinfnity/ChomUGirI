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

/** Commands whose whole point is reading a file's contents back to the model. */
private val READ_COMMAND = Regex(
    """\b(cat|head|tail|less|more|sed\s+-n|awk|xxd|hexdump|strings)\s+(\S+)""",
)

/** Best-effort extraction of the path being read, for the confirmation prompt. */
fun readTarget(command: String): String? = READ_COMMAND.find(command)?.groupValues?.get(2)

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
    /** Called before a file-read command runs; the run stops if this returns false. */
    onConfirmRead: suspend (path: String) -> Boolean = { true },
    /** Project env vars — actually exported into the shell before any build command runs. */
    envVars: Map<String, String> = emptyMap(),
): Flow<PipelineEvent> = flow {
    if (!TerminalClient.connected.value) {
        // Names the actual next step rather than just stating the problem: "not connected" tells
        // someone nothing about what to go do, and the APK genuinely is buildable the moment the
        // terminal is up, so say that too instead of leaving it sounding like a refusal.
        emit(PipelineEvent.Failed(
            "Your terminal isn't running, so there's nothing to build on yet. Start ttyd on your " +
                "machine, expose it with your tunnel, and paste that URL in Settings > My Terminal " +
                "— then tap Build again and this will compile the APK for you."
        ))
        return@flow
    }
    if (!settings.agentTerminalEnabled) {
        emit(PipelineEvent.Failed("Agent terminal access is off. Turn it on in Settings to let ChomuGiri run commands."))
        return@flow
    }

    try {
        emit(PipelineEvent.Step("Preparing $workDir on your machine..."))
        val mk = TerminalClient.runCommand("mkdir -p $workDir && cd $workDir && pwd", 60_000)
        val resolvedDir = mk.output.lines().lastOrNull { it.trim().startsWith("/") }?.trim() ?: workDir
        emit(PipelineEvent.Step("Working directory: $resolvedDir", done = true))

        if (envVars.isNotEmpty()) {
            emit(PipelineEvent.Step("Exporting ${envVars.size} env var(s)..."))
            for ((name, value) in envVars) {
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
                val r = TerminalClient.runCommand("export $name=\"\$(echo '${b64(value)}' | base64 -d)\"", 30_000)
                if (r.exitCode != 0) {
                    emit(PipelineEvent.Failed("Failed to export $name: ${r.output.take(300)}"))
                    return@flow
                }
            }
            emit(PipelineEvent.Step("Env vars exported.", done = true))
        }

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

        // An upfront plan so the user sees the scope before any command runs, instead of only
        // finding out step by step — this is announced once, not asked as a question.
        emit(PipelineEvent.Step("Planning..."))
        val planRaw = LlmClient.complete(
            settings.provider(RoleKey.KIMI), "Build agent",
            listOf(
                ChatTurn("system", "You plan work, you do not execute it yet."),
                ChatTurn(
                    "user",
                    "Goal: $goal\n\nIn 2-4 short bullet lines, state the plan you'll follow. No preamble.",
                ),
            ),
            temperature = 0.2, maxTokens = 300,
        )
        emit(PipelineEvent.Chunk(planRaw.trim() + "\n"))
        emit(PipelineEvent.Step("Plan ready — starting.", done = true))

        val history = mutableListOf(
            ChatTurn("system", TERMINAL_AGENT_PROMPT),
            ChatTurn(
                "user",
                buildString {
                    append("Goal: $goal\n\n")
                    append("Your plan:\n$planRaw\n\n")
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

            // The command is typed into a real PTY, so an embedded newline is an Enter keypress:
            // the shell would run the first fragment on its own and sit at a continuation prompt
            // for the rest, and the end marker would land in the wrong place. Rather than mangling
            // it silently, hand the problem back to the model — it gets another turn anyway, and a
            // model that wanted a script can write it with && or a heredoc-free one-liner.
            if (command.contains('\n')) {
                emit(PipelineEvent.Step("[$step] rejected a multi-line command — asking for one line", done = true))
                history += ChatTurn("assistant", raw)
                history += ChatTurn(
                    "user",
                    "That command spans multiple lines, which this shell cannot accept. " +
                        "Send exactly one single-line command (chain steps with && or ;).",
                )
                continue
            }

            readTarget(command)?.let { path ->
                emit(PipelineEvent.Step("Waiting for permission to read $path...", done = true))
                if (!onConfirmRead(path)) {
                    emit(PipelineEvent.Failed("Read of $path was not allowed — stopping."))
                    return@flow
                }
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
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emit(PipelineEvent.Failed(e.message ?: "Terminal agent failed."))
    }
}

/**
 * True when the project is already a real native Android/Gradle project rather than a web app.
 *
 * This matters because the two cases need completely different build commands, and getting it
 * wrong wastes the entire run: the coder now emits real Kotlin/Compose Gradle projects for any
 * "make me an APK" request (see ANDROID_PROJECT_INSTRUCTIONS), and the old goal below assumed
 * every project was a web app and tried to wrap it in Capacitor — which cannot work on a project
 * that is already an Android app and fails after minutes of setup.
 */
fun isNativeAndroidProject(files: List<GeneratedFile>): Boolean =
    files.any { it.path.endsWith("settings.gradle.kts") || it.path.endsWith("settings.gradle") } &&
        files.any { it.path.endsWith("AndroidManifest.xml") }

/** The concrete goal used by the "Build real APK" action, matched to what the project actually is. */
fun apkBuildGoal(projectName: String, files: List<GeneratedFile> = emptyList()): String =
    if (isNativeAndroidProject(files)) nativeApkBuildGoal() else webApkBuildGoal(projectName)

private fun nativeApkBuildGoal(): String = """
Build a real debug APK from the native Android project in this directory.

This is already a complete Gradle project (settings.gradle.kts + AndroidManifest.xml are present).
Do NOT use Capacitor, Cordova, npm or a 'www' directory — none of those apply here.

 1. Check that java is available and report its version; report clearly if it is missing.
 2. Make sure ANDROID_HOME (or ANDROID_SDK_ROOT) points at an Android SDK; report clearly if not.
 3. If there is no gradle wrapper (./gradlew), use a system 'gradle' if one exists; if neither is
    available, say so plainly instead of guessing.
 4. Run the debug build (./gradlew assembleDebug --no-daemon, or gradle assembleDebug --no-daemon).
 5. If it fails to compile, read the actual error, fix the offending file, and build again — up to
    three attempts. Report the real compiler error if it still fails.
 6. Print the absolute path of the produced .apk file.

If a required tool or the Android SDK is genuinely missing, stop and say exactly what the user
needs to install — do not pretend the build succeeded.
""".trim()

private fun webApkBuildGoal(projectName: String): String = """
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
