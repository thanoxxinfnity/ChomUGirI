# ChomuGirI — native Android app (v1.2)

A real native app in Kotlin + Jetpack Compose. Not a WebView wrapper: no web bundle, no
`server.url`, nothing loaded from a website. Every screen is Compose, every network call is
made from the app itself.

## Build

```bash
echo "sdk.dir=/path/to/your/Android/sdk" > local.properties
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 36. `local.properties` is deliberately not
committed — it is machine-specific.

## What is in here

| Area | File |
| --- | --- |
| Auto routing (chat vs build vs research) | `core/Router.kt` |
| Coder/auditor swarm | `core/Pipeline.kt` |
| Deep Research (plan → search → cite) | `core/DeepResearch.kt` |
| Terminal build agent | `core/AgentRunner.kt` |
| OpenAI-compatible streaming client | `net/LlmClient.kt` |
| ttyd WebSocket terminal | `net/TerminalClient.kt` |
| Web search (Tavily/Brave/Serper) | `net/SearchClient.kt` |
| `.zip` export via SAF | `core/ZipExport.kt` |

## Honest notes

These are real constraints, not missing work:

- **An APK cannot be compiled on the phone.** An Android app has no JDK, Gradle, or Android SDK
  in its sandbox. The "Build APK" action therefore runs the build on *your* machine over the
  terminal you connected, and shows you the real command output. Your machine needs node, npm,
  a JDK, and an Android SDK with `ANDROID_HOME` set.
- **Deep Research needs a web search API key** (Tavily, Brave, or Serper). Without one it
  refuses to run rather than letting the model answer from memory — that would be invention,
  not research.
- **No key of any kind is compiled in.** API keys, the terminal URL, and the search key are all
  entered in Settings and stored only on the device. The app never creates or hosts a tunnel;
  the terminal URL is entirely the user's own.
- The AI can only run shell commands when *Let ChomuGirI run commands* is switched on in
  Settings. Obviously destructive commands are refused client-side regardless.

## Verified

`TerminalClient` was tested against a real `ttyd 1.7.4` server: connection and auth framing,
per-command exit codes (including non-zero and explicit codes), multi-line output, quoting,
stderr capture, ANSI stripping, base64 file transfer round-trip (unicode included), and the
end-marker guard that stops a command's own echo from ending its capture early. 37/37 passed.

Not verified: the app has not been launched on a device or emulator from the build environment
(no KVM available there), so the UI itself is compile- and lint-clean but not runtime-exercised.
