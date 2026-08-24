package com.chomugiri.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class CommandResult(val output: String, val exitCode: Int, val timedOut: Boolean = false)

private const val ESC = "\u001B"
private const val BEL = "\u0007"

private val ANSI = Regex(
    // CSI sequences, OSC sequences (BEL-terminated), then lone two-character escapes.
    ESC + "\\[[0-?]*[ -/]*[@-~]" + "|" + ESC + "\\][^" + BEL + "]*" + BEL + "|" + ESC + "[@-Z\\\\-_]"
)

fun stripAnsi(s: String): String =
    ANSI.replace(s, "").replace("\r\n", "\n").replace('\r', '\n')

/**
 * Turns a failed WebSocket upgrade into something a person can act on. A bare
 * "connection failed" is useless when the real cause is "your tunnel isn't running" —
 * these are the cases people actually hit with ttyd behind ngrok.
 */
fun explainWsFailure(code: Int?, body: String?, errorMessage: String?): String {
    val b = body.orEmpty()
    return when {
        b.contains("ERR_NGROK_3200") || b.contains("is offline") ->
            "Tunnel is offline — start ttyd, then start ngrok pointing at its port."

        b.contains("ERR_NGROK_8012") || code == 502 || code == 504 ->
            "Tunnel is up but nothing is listening on that port — is ttyd running?"

        b.contains("ERR_NGROK") && code == 402 ->
            "ngrok rejected the request (account limit reached)."

        code == 401 || code == 403 ->
            "Terminal refused the connection ($code) — check the ttyd credentials/token."

        code == 404 ->
            "Nothing at that address ($code) — check the URL, and that ttyd is on the port ngrok forwards to."

        code == 200 ->
            "That URL answered but is not a ttyd terminal — it did not accept a WebSocket upgrade."

        code != null -> "Connection refused (HTTP $code)."

        errorMessage?.contains("UnknownHost", true) == true ||
            errorMessage?.contains("Unable to resolve", true) == true ->
            "Can't resolve that hostname — check the URL and your connection."

        errorMessage?.contains("timeout", true) == true ->
            "Connection timed out — the tunnel may be down."

        else -> "Disconnected: ${errorMessage ?: "connection failed"}"
    }
}

private fun explainFailure(t: Throwable, response: Response?): String {
    val body = response?.let { r -> runCatching { r.body?.string() }.getOrNull() }
    return explainWsFailure(response?.code, body, t.message)
}

/** ttyd frames are a single ASCII command byte followed by the payload. */
private fun frame(s: String): ByteString = s.toByteArray(Charsets.UTF_8).toByteString()

/**
 * Speaks ttyd's WebSocket protocol against a terminal the *user* hosts and exposes themselves.
 * This app never provisions or hardcodes a tunnel — the URL comes purely from Settings.
 *
 * ttyd framing: client sends '0'+input / '1'+resize-json; server sends '0'+output /
 * '1'+title / '2'+prefs. The first client frame must be the auth-token JSON.
 */
object TerminalClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a terminal stream is long-lived
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val counter = AtomicLong(0)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Rolling screen buffer for display. */
    private val _screen = MutableStateFlow("")
    val screen: StateFlow<String> = _screen.asStateFlow()

    /** Recently typed commands, newest first — lets the UI offer tap-to-recall instead of arrow keys. */
    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()
    private const val MAX_HISTORY = 30

    fun recordHistory(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        _commandHistory.value = (listOf(trimmed) + _commandHistory.value.filterNot { it == trimmed }).take(MAX_HISTORY)
    }

    private const val MAX_SCREEN = 120_000

    /** The literal split by `""` so the PTY's echo can never match the real completion line. */
    private const val ECHO_GUARD = "__CHOMU\"\"_END_"

    private class Capture(
        val marker: Regex,
        val sb: StringBuilder,
        val deferred: CompletableDeferred<CommandResult>,
    )

    private val captures = mutableListOf<Capture>()

    /**
     * A phone keyboard's autocorrect can silently swap a typed "-" for a lookalike Unicode dash —
     * invisible in a monospace terminal font, but OkHttp's HttpUrl rejects the resulting host
     * outright ("Invalid URL host") since it is no longer plain ASCII. Verified live: the exact
     * same hostname retyped with a real ASCII hyphen resolves fine over the network; only the
     * substituted character breaks it. Mapped back to ASCII here so a hand-typed URL can't
     * silently stop working this way. Written entirely as \u escapes below, not literal
     * characters, so this fix can't itself fall victim to the same class of mangling.
     */
    private val LOOKALIKE_DASHES = "[\u2010\u2011\u2012\u2013\u2014\u2212]".toRegex()
    private val INVISIBLE_CHARS = "[\u00a0\u2009\u200a\u2007\u2008\u2002\u2003\u200b\u200c\u200d\u3000\ufeff]".toRegex()

    /** Turns whatever the user pasted into a ttyd websocket URL. */
    fun normalizeUrl(raw: String): String {
        var u = raw
            // Every whitespace character anywhere in the string, not just the ends. A wrapped
            // copy-paste puts a real newline in the middle of the host — the reported URL was
            // literally "...ngrok\n-free.dev" — and trim() only touches the outside, so the
            // newline survived into the host and OkHttp rejected it as "Invalid URL host".
            // A URL can never legally contain whitespace, so dropping all of it is always right.
            .replace(Regex("\\s+"), "")
            .trimEnd('/')
            .replace(LOOKALIKE_DASHES, "-")
            .replace(INVISIBLE_CHARS, "")
        if (u.isEmpty()) return u
        u = when {
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("ws://") || u.startsWith("wss://") -> u
            else -> "wss://$u"
        }
        if (!u.endsWith("/ws")) u = "$u/ws"
        return u
    }

    /**
     * The URL is whatever the user typed by hand, so it can be malformed in ways OkHttp's
     * builder rejects outright (throws IllegalArgumentException before any network call even
     * starts) — that must turn into a status message, not a crash on the calling thread.
     */
    fun connect(rawUrl: String, authToken: String) {
        disconnect()
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) {
            _status.value = "No terminal URL set"
            return
        }
        _status.value = "Connecting..."
        appendScreen("\n[connecting to $url]\n")

        val req = try {
            Request.Builder()
                .url(url)
                .addHeader("Sec-WebSocket-Protocol", "tty")
                // ngrok's free tier serves a browser interstitial that would swallow the upgrade.
                // The header is the documented opt-out; the non-browser UA keeps it from triggering
                // in the first place.
                .addHeader("ngrok-skip-browser-warning", "true")
                .addHeader("User-Agent", "ChomuGirI-Terminal/1.2")
                .build()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = "That doesn't look like a valid URL: ${e.message ?: "malformed URL"}"
            _status.value = why
            appendScreen("\n[$why]\n")
            return
        }

        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                _status.value = "Connected"
                appendScreen("[connected]\n")
                // ttyd requires the auth frame first, then a sane window size.
                webSocket.send(JSONObject().put("AuthToken", authToken).toString())
                webSocket.send(frame("1" + JSONObject().put("columns", 100).put("rows", 30)))
                webSocket.send(frame("0\n")) // nudge the shell so a prompt appears
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleFrame(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                val why = explainFailure(t, response)
                _status.value = why
                appendScreen("\n[$why]\n")
                failAllCaptures(why)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                _status.value = "Closed ($code)"
                appendScreen("\n[closed: $code $reason]\n")
                failAllCaptures("Terminal closed")
            }
        })
    }

    private fun handleFrame(raw: String) {
        if (raw.isEmpty()) return
        when (raw[0]) {
            '0' -> {
                val data = raw.substring(1)
                appendScreen(data)
                feedCaptures(stripAnsi(data))
            }
            '1', '2' -> Unit // window title / preferences — nothing to show
            else -> {
                appendScreen(raw)
                feedCaptures(stripAnsi(raw))
            }
        }
    }

    private fun appendScreen(s: String) {
        val next = _screen.value + s
        _screen.value = if (next.length > MAX_SCREEN) next.takeLast(MAX_SCREEN) else next
    }

    fun clearScreen() {
        _screen.value = ""
    }

    private fun feedCaptures(text: String) {
        synchronized(captures) {
            val finished = mutableListOf<Capture>()
            for (c in captures) {
                c.sb.append(text)
                val m = c.marker.find(c.sb)
                if (m != null) {
                    val body = c.sb.substring(0, m.range.first)
                    val code = m.groupValues[1].toIntOrNull() ?: -1
                    c.deferred.complete(CommandResult(cleanOutput(body), code))
                    finished += c
                }
            }
            captures.removeAll(finished)
        }
    }

    private fun failAllCaptures(reason: String) {
        synchronized(captures) {
            captures.forEach { it.deferred.complete(CommandResult(reason, -1)) }
            captures.clear()
        }
    }

    /** Drops the PTY's echo of the command line we just typed. */
    private fun cleanOutput(raw: String): String =
        raw.lineSequence()
            .filterNot { it.contains(ECHO_GUARD) }
            .joinToString("\n")
            .trim()

    /** Raw keystrokes from the user's own typing. */
    fun sendInput(text: String) {
        socket?.send(frame("0$text"))
    }

    /**
     * Runs one command and waits for it to actually finish, keying off an end-marker the shell
     * itself echoes along with the real exit code.
     */
    suspend fun runCommand(command: String, timeoutMs: Long = 300_000): CommandResult {
        val ws = socket
        if (ws == null || !_connected.value) {
            return CommandResult("Terminal is not connected.", -1)
        }
        val id = counter.incrementAndGet()
        val marker = Regex("__CHOMU_END_${id}_(-?\\d+)__")
        val deferred = CompletableDeferred<CommandResult>()
        val capture = Capture(marker, StringBuilder(), deferred)
        synchronized(captures) { captures += capture }

        val line = "{ $command ; } 2>&1 ; echo \"__CHOMU\"\"_END_${id}_\$?__\"\n"
        ws.send(frame("0$line"))

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            CommandResult(cleanOutput(capture.sb.toString()), -1, timedOut = true)
        } finally {
            // Must run on every exit path, not just the timeout one. When the caller's coroutine
            // is cancelled (the user stops a build mid-command), the plain CancellationException
            // propagates straight past a timeout-only catch and the capture stays registered
            // forever — with every subsequent byte of terminal output still being appended to its
            // StringBuilder. Over a long session that is an unbounded leak on a dead command.
            synchronized(captures) { captures.remove(capture) }
        }
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
        _status.value = "Not connected"
        failAllCaptures("Terminal disconnected")
    }
}
