package com.chomugiri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chomugiri.app.core.*
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.net.SearchClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var quickKey by remember { mutableStateOf("") }

    val bg by androidx.compose.animation.animateColorAsState(BgDark, androidx.compose.animation.core.tween(200), label = "bg")
    Column(Modifier.fillMaxSize().background(bg)) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section("Appearance") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("dark" to "Dark", "light" to "Light").forEach { (mode, label) ->
                        val selected = settings.themeMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { vm.updateSettings { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                containerColor = BgElevated2,
                            ),
                        )
                    }
                }
            }

            Section("API keys") {
                Text(
                    "Your keys are stored only on this device and are sent straight to the provider " +
                        "you point them at. Nothing is baked into the app and nothing goes anywhere else. " +
                        "The Android app has its own storage — a key you set in a browser does not carry over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )

                Spacer(Modifier.height(10.dp))
                Field(
                    label = "Quick fill — one NVIDIA NIM key for every role",
                    value = quickKey,
                    onChange = { quickKey = it },
                    secret = true,
                )
                Button(
                    onClick = {
                        val k = quickKey.trim()
                        if (k.isNotEmpty()) {
                            vm.updateSettings { s ->
                                var next = s
                                ROLE_ORDER.forEach { role ->
                                    next = next.withProvider(
                                        role,
                                        next.provider(role).copy(apiKey = k, baseUrl = NIM_BASE_URL),
                                    )
                                }
                                next
                            }
                            quickKey = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Apply to all 5 roles") }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Model ids you set earlier are kept as-is, so a newer recommended default " +
                        "never silently overwrites your choice. This applies the current " +
                        "recommendations (keeping your keys) — worth doing if your coder role is " +
                        "still on an older model, since that is what actually writes the code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                OutlinedButton(onClick = {
                    vm.updateSettings { s ->
                        var next = s
                        ROLE_ORDER.forEach { role ->
                            next = next.withProvider(role, next.provider(role).copy(model = defaultModelFor(role)))
                        }
                        next
                    }
                }) { Text("Use recommended models for all roles", color = Accent2) }
            }

            ROLE_ORDER.forEach { role ->
                val cfg = settings.provider(role)
                Section(ROLE_LABELS[role] ?: role.name) {
                    Field("API key", cfg.apiKey, secret = true) { v ->
                        vm.updateSettings { it.withProvider(role, it.provider(role).copy(apiKey = v)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Field("Base URL", cfg.baseUrl) { v ->
                        vm.updateSettings { it.withProvider(role, it.provider(role).copy(baseUrl = v)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Field("Model", cfg.model) { v ->
                        vm.updateSettings { it.withProvider(role, it.provider(role).copy(model = v)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        vm.updateSettings {
                            it.withProvider(
                                role,
                                it.provider(role).copy(
                                    apiKey = "",
                                    baseUrl = com.chomugiri.app.net.POLLINATIONS_BASE_URL,
                                    model = com.chomugiri.app.net.POLLINATIONS_DEFAULT_MODEL,
                                ),
                            )
                        }
                    }) {
                        Text("Use free Pollinations model for this role — no key needed", color = Success, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Or use real Claude via OpenRouter — needs your own OpenRouter key with credits, " +
                            "never free (verified live: Opus 5 is \$5/\$25 per 1M tokens there, not \$0).",
                        style = MaterialTheme.typography.labelSmall,
                        color = FgMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "Opus 5" to "anthropic/claude-opus-5",
                            "Sonnet 5" to "anthropic/claude-sonnet-5",
                            "Opus 4.8" to "anthropic/claude-opus-4.8",
                            "Haiku 4.5" to "anthropic/claude-haiku-4.5",
                        ).forEach { (label, slug) ->
                            Surface(
                                color = BgDark,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    vm.updateSettings {
                                        it.withProvider(
                                            role,
                                            it.provider(role).copy(
                                                baseUrl = OPENROUTER_BASE_URL,
                                                model = slug,
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text(
                                    label,
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    ProviderHealthCheck(role, cfg)
                }
            }

            Section("My Terminal") {
                Text(
                    "Run ttyd on your own machine and expose it with your own tunnel. This URL " +
                        "is never pre-filled or shared — everyone who installs this app enters " +
                        "their own, because whoever holds a URL gets a real shell on that machine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Field("Terminal URL (https:// or wss://)", settings.terminalUrl) { v ->
                    vm.updateSettings { it.copy(terminalUrl = v) }
                }
                Spacer(Modifier.height(8.dp))
                Field("ttyd auth token (optional)", settings.terminalAuthToken, secret = true) { v ->
                    vm.updateSettings { it.copy(terminalAuthToken = v) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.autoConnectTerminal,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(autoConnectTerminal = on) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connect automatically", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Connects at startup and keeps retrying if the tunnel is down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FgMuted,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.agentTerminalEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(agentTerminalEnabled = on) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Let ChomuGiri run commands", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off means only you can type. On means the AI can run real commands " +
                                "on your machine — that is what makes the APK build work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FgMuted,
                        )
                    }
                }
            }

            Section("Deep Research") {
                Text(
                    "Defaults to DuckDuckGo — real web search and real page reads, no key needed. " +
                        "Switch to Tavily/Brave/Serper for a paid provider's results instead. There " +
                        "is still no fallback to the model just answering from memory — that isn't research.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchClient.PROVIDERS.forEach { p ->
                        FilterChip(
                            selected = settings.searchProvider == p,
                            onClick = { vm.updateSettings { it.copy(searchProvider = p) } },
                            label = { Text(if (p == "duckduckgo") "DuckDuckGo" else p.replaceFirstChar { c -> c.uppercase() }) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                containerColor = BgElevated,
                            ),
                        )
                    }
                }
                if (settings.searchProvider != "duckduckgo") {
                    Spacer(Modifier.height(8.dp))
                    Field("Search API key", settings.searchApiKey, secret = true) { v ->
                        vm.updateSettings { it.copy(searchApiKey = v) }
                    }
                }
            }

            Section("Image Generation (Gemini)") {
                Text(
                    "Real generated images for projects — used automatically wherever the coder " +
                        "wants a real photo/illustration instead of a broken placeholder. Get a " +
                        "key from Google AI Studio. With no key set, those spots get a plain " +
                        "gradient instead — never a broken image link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Field("Gemini API key", settings.geminiApiKey, secret = true) { v ->
                    vm.updateSettings { it.copy(geminiApiKey = v) }
                }
                Spacer(Modifier.height(8.dp))
                Field("Model", settings.geminiModel) { v ->
                    vm.updateSettings { it.copy(geminiModel = v) }
                }
                Spacer(Modifier.height(10.dp))
                GeminiHealthCheck(settings.geminiApiKey, settings.geminiModel)
            }

            Section("Video Generation (Hugging Face)") {
                Text(
                    "Real text-to-video via Hugging Face's Inference Providers (fal.ai/Wan2.2). " +
                        "Get a free token from huggingface.co/settings/tokens — free accounts get a " +
                        "small included monthly credit, not unlimited (video is too GPU-expensive " +
                        "for any provider to give away without limit). Create a token scoped to " +
                        "just \"Inference Providers\" rather than a broad one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Field("Hugging Face token", settings.huggingfaceToken, secret = true) { v ->
                    vm.updateSettings { it.copy(huggingfaceToken = v) }
                }
                Spacer(Modifier.height(8.dp))
                Field("Provider/model path", settings.huggingfaceVideoModel) { v ->
                    vm.updateSettings { it.copy(huggingfaceVideoModel = v) }
                }
                Spacer(Modifier.height(10.dp))
                HuggingFaceHealthCheck(settings.huggingfaceToken, settings.huggingfaceVideoModel)
            }

            Section("Deploy") {
                Text(
                    "Needed for the Deploy button on a project. Get a token from Vercel's " +
                        "Account Settings → Tokens. Stored only on this device, sent only to Vercel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Field("Vercel token", settings.vercelToken, secret = true) { v ->
                    vm.updateSettings { it.copy(vercelToken = v) }
                }
            }

            Section("Pipeline") {
                val tierLabel = com.chomugiri.app.core.POWER_TIERS
                    .firstOrNull { it.auditLoops == settings.maxAuditLoops }?.label
                Text(
                    "Audit rounds: ${settings.maxAuditLoops}${tierLabel?.let { " ($it)" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Same as the tier chips above the composer. Every round is a real model " +
                        "call, so more rounds means a genuinely longer wait — 0 skips the audit " +
                        "and safety passes entirely for raw speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Slider(
                    value = settings.maxAuditLoops.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(maxAuditLoops = v.toInt()) } },
                    valueRange = 0f..7f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private sealed class PingUiState {
    data object Loading : PingUiState()
    data class Done(val ok: Boolean, val latencyMs: Long, val message: String) : PingUiState()
}

/** A real, minimal round-trip to the provider — proves the key/model/base-url actually work. */
@Composable
private fun ProviderHealthCheck(role: RoleKey, cfg: ProviderConfig) {
    var state by remember(role, cfg) { mutableStateOf<PingUiState?>(null) }
    val scope = rememberCoroutineScope()

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                state = PingUiState.Loading
                scope.launch {
                    val r = com.chomugiri.app.net.LlmClient.ping(cfg, ROLE_LABELS[role] ?: role.name)
                    state = PingUiState.Done(r.ok, r.latencyMs, r.message)
                }
            },
            enabled = state !is PingUiState.Loading,
        ) {
            Icon(Icons.Default.NetworkCheck, null, tint = Accent2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Test connection", color = Accent2, style = MaterialTheme.typography.bodySmall)
        }
        when (val s = state) {
            is PingUiState.Loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent2)
            is PingUiState.Done -> Text(
                if (s.ok) "OK · ${s.latencyMs}ms" else s.message.take(60),
                style = MaterialTheme.typography.labelSmall,
                color = if (s.ok) Success else Danger,
                maxLines = 1,
            )
            null -> Unit
        }
    }
}

/** A real, tiny image-generation call — proves the Gemini key/model actually work end to end. */
@Composable
private fun GeminiHealthCheck(apiKey: String, model: String) {
    var state by remember(apiKey, model) { mutableStateOf<PingUiState?>(null) }
    val scope = rememberCoroutineScope()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    state = PingUiState.Loading
                    scope.launch {
                        val started = System.currentTimeMillis()
                        state = try {
                            com.chomugiri.app.net.GeminiClient.generateImageDataUri(apiKey, model, "a small blue circle on a white background")
                            PingUiState.Done(true, System.currentTimeMillis() - started, "OK")
                        } catch (e: Exception) {
                            PingUiState.Done(false, System.currentTimeMillis() - started, e.message ?: "Failed")
                        }
                    }
                },
                enabled = state !is PingUiState.Loading && apiKey.isNotBlank(),
            ) {
                Icon(Icons.Default.NetworkCheck, null, tint = Accent2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test image generation", color = Accent2, style = MaterialTheme.typography.bodySmall)
            }
            if (state is PingUiState.Loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent2)
            } else if (state is PingUiState.Done && (state as PingUiState.Done).ok) {
                Text("OK · ${(state as PingUiState.Done).latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = Success)
            }
        }
        (state as? PingUiState.Done)?.let { s ->
            if (!s.ok) {
                Text(s.message, style = MaterialTheme.typography.labelSmall, color = Danger, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** A real, tiny video-generation call — proves the HF token/model path actually work end to end.
 * Genuinely costs a sliver of the account's monthly credit, same as any other real generation. */
@Composable
private fun HuggingFaceHealthCheck(token: String, modelPath: String) {
    var state by remember(token, modelPath) { mutableStateOf<PingUiState?>(null) }
    val scope = rememberCoroutineScope()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    state = PingUiState.Loading
                    scope.launch {
                        val started = System.currentTimeMillis()
                        state = try {
                            com.chomugiri.app.net.HuggingFaceClient.generateVideo(token, modelPath, "a small blue circle spinning")
                            PingUiState.Done(true, System.currentTimeMillis() - started, "OK")
                        } catch (e: Exception) {
                            PingUiState.Done(false, System.currentTimeMillis() - started, e.message ?: "Failed")
                        }
                    }
                },
                enabled = state !is PingUiState.Loading && token.isNotBlank(),
            ) {
                Icon(Icons.Default.NetworkCheck, null, tint = Accent2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test video generation (uses real credit)", color = Accent2, style = MaterialTheme.typography.bodySmall)
            }
            if (state is PingUiState.Loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent2)
            } else if (state is PingUiState.Done && (state as PingUiState.Done).ok) {
                Text("OK · ${(state as PingUiState.Done).latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = Success)
            }
        }
        (state as? PingUiState.Done)?.let { s ->
            if (!s.ok) {
                Text(s.message, style = MaterialTheme.typography.labelSmall, color = Danger, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = BgElevated, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent.copy(alpha = 0.6f),
            unfocusedBorderColor = BorderCol,
            focusedContainerColor = BgElevated2,
            unfocusedContainerColor = BgElevated2,
        ),
    )
}
