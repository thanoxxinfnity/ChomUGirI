package com.chomugiri.app.core

import android.util.Base64
import com.chomugiri.app.net.TerminalClient
import java.security.MessageDigest

/**
 * Brings a file built on the user's own machine back to the phone, through the same ttyd
 * WebSocket the agent already uses. There is no other channel — the terminal is the only thing
 * connecting the two — so the bytes travel as base64 through the PTY.
 *
 * The transfer is chunked rather than one giant command for two reasons: a PTY is a stream with
 * no framing of its own, so a single multi-megabyte burst is far more likely to be truncated or
 * to stall than a sequence of bounded reads; and chunking is what makes real progress reporting
 * possible instead of a frozen spinner for several minutes.
 *
 * Every transfer is checked against the file's real sha256 on the remote side. A silent single-bit
 * corruption would produce an APK that installs and then misbehaves, which is far worse than a
 * transfer that admits it failed.
 */
object ApkFetch {

    /** Base64 characters pulled per command. ~700KB of base64 is ~525KB of binary per round trip. */
    private const val CHUNK_CHARS = 700_000

    /**
     * Refuse rather than spend twenty minutes on a transfer that was never going to be pleasant.
     * A debug APK from a generated project is a few MB; something far larger means the build
     * produced something unexpected and the user should know that rather than wait it out.
     */
    private const val MAX_BYTES = 80L * 1024 * 1024

    private const val REMOTE_B64 = "/tmp/.chomugiri-transfer.b64"

    sealed class Result {
        data class Ok(val bytes: ByteArray, val remotePath: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Finds the newest .apk under [dir]. The agent is asked to print the path itself, but models
     * paraphrase, wrap it in prose, or print a relative path — searching for the real artifact is
     * the reliable answer, and it costs one command.
     */
    /**
     * Turns whatever the caller passed into a real absolute path first.
     *
     * The default build directory is "~/chomugiri-build", and a tilde inside single quotes is not
     * expanded by the shell — `find '~/chomugiri-build'` genuinely looks for a directory literally
     * named "~", finds nothing, and reports success. Every path below has to be quoted (build
     * directories can contain spaces), so the tilde is resolved once, up front, instead.
     */
    private suspend fun resolveDir(dir: String): String {
        if (dir.startsWith("/")) return dir
        val r = TerminalClient.runCommand("cd $dir 2>/dev/null && pwd", 60_000)
        return r.output.lines().map { it.trim() }.lastOrNull { it.startsWith("/") } ?: dir
    }

    suspend fun findApk(rawDir: String): String? {
        val dir = resolveDir(rawDir)
        // "newest .apk" alone is wrong, and was caught doing the wrong thing in testing: a Gradle
        // build also writes intermediates (app-debug-unaligned.apk) and can leave a zero-byte file
        // behind, and an intermediate is frequently the newest one on disk. Handing the user an
        // unaligned intermediate produces an APK that simply will not install. So: skip empty
        // files, prefer anything under outputs/apk (the real artifact directory), and only fall
        // back to plain newest if the build put its output somewhere non-standard.
        // Deliberately one line. runCommand types the command into a real PTY, so an embedded
        // newline is an Enter keypress — the shell would start executing a half-written command.
        val cmd = "find '$dir' -name '*.apk' -type f -size +0 -printf '%T@ %p\\n' 2>/dev/null " +
            "| sort -rn > /tmp/.chomu-apks ; grep -m1 'outputs/apk' /tmp/.chomu-apks || head -1 /tmp/.chomu-apks"
        val r = TerminalClient.runCommand(cmd, 120_000)
        val line = r.output.lines().map { it.trim() }.lastOrNull { it.endsWith(".apk") } ?: return null
        // Each line is "<mtime> <path>"; the path can itself contain spaces, so only the first
        // field is dropped rather than splitting the whole line.
        return line.substringAfter(' ').trim().takeIf { it.endsWith(".apk") }
    }

    /**
     * @param onProgress called with (bytesReceived, totalBytes) as each chunk lands.
     */
    suspend fun pull(remotePath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result {
        val sizeOut = TerminalClient.runCommand("wc -c < '$remotePath'", 60_000)
        val size = sizeOut.output.lines().mapNotNull { it.trim().toLongOrNull() }.lastOrNull()
            ?: return Result.Failed("Couldn't read the size of $remotePath — is the file really there?")
        if (size <= 0L) return Result.Failed("The built file is empty (0 bytes).")
        if (size > MAX_BYTES) {
            return Result.Failed("That APK is ${size / 1024 / 1024}MB — too large to pull back through the terminal.")
        }

        val expectedSha = TerminalClient.runCommand("sha256sum '$remotePath' | cut -d' ' -f1", 120_000)
            .output.lines().map { it.trim() }.lastOrNull { it.length == 64 && it.all(Char::isLetterOrDigit) }

        // Encoded once into a temp file, then read in slices. Re-encoding per chunk would re-read
        // the whole APK for every slice, turning a linear transfer into a quadratic one.
        val enc = TerminalClient.runCommand(
            "base64 -w 0 '$remotePath' > $REMOTE_B64 && wc -c < $REMOTE_B64", 300_000,
        )
        val b64Len = enc.output.lines().mapNotNull { it.trim().toLongOrNull() }.lastOrNull()
            ?: return Result.Failed("base64 isn't available on your machine, so the APK can't be sent back.")

        val sb = StringBuilder(b64Len.toInt().coerceAtLeast(16))
        var offset = 1L // cut -c is 1-indexed
        while (offset <= b64Len) {
            val end = minOf(offset + CHUNK_CHARS - 1, b64Len)
            val chunk = TerminalClient.runCommand("cut -c$offset-$end $REMOTE_B64", 300_000)
            if (chunk.timedOut) return Result.Failed("The transfer stalled partway through. Try again.")
            // The PTY wraps long lines and injects its own newlines; base64 payload never contains
            // whitespace, so stripping all of it is safe and is what un-wraps the chunk.
            val cleaned = chunk.output.filterNot { it.isWhitespace() }
            if (cleaned.isEmpty()) return Result.Failed("The transfer returned nothing at byte $offset.")
            sb.append(cleaned)
            offset = end + 1
            onProgress((sb.length * 3L / 4).coerceAtMost(size), size)
        }

        TerminalClient.runCommand("rm -f $REMOTE_B64", 30_000)

        val bytes = try {
            Base64.decode(sb.toString(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Failed("The transferred data was corrupted in transit (bad base64).")
        }

        if (bytes.size.toLong() != size) {
            return Result.Failed("Transfer incomplete — got ${bytes.size} bytes, expected $size.")
        }
        if (expectedSha != null) {
            val got = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!got.equals(expectedSha, ignoreCase = true)) {
                return Result.Failed("Checksum mismatch — the APK was corrupted in transit, so it wasn't saved.")
            }
        }
        return Result.Ok(bytes, remotePath)
    }
}
