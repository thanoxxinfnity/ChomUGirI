package com.chomugiri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chomugiri.app.core.*
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.net.SearchClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var quickKey by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
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
                        Text("Let ChomuGirI run commands", style = MaterialTheme.typography.bodyMedium)
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
                    "Needs a web search key. Without one Deep Research stays off on purpose — an " +
                        "LLM answering from memory is not research, and pretending otherwise would " +
                        "just be making things up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchClient.PROVIDERS.forEach { p ->
                        FilterChip(
                            selected = settings.searchProvider == p,
                            onClick = { vm.updateSettings { it.copy(searchProvider = p) } },
                            label = { Text(p.replaceFirstChar { c -> c.uppercase() }) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                containerColor = BgElevated,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field("Search API key", settings.searchApiKey, secret = true) { v ->
                    vm.updateSettings { it.copy(searchApiKey = v) }
                }
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
