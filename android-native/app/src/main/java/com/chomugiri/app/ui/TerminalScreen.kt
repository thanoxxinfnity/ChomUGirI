package com.chomugiri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.net.TerminalClient

@Composable
fun TerminalScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val connected by TerminalClient.connected.collectAsState()
    val status by TerminalClient.status.collectAsState()
    val screen by TerminalClient.screen.collectAsState()

    var input by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    val history by TerminalClient.commandHistory.collectAsState()
    val scroll = rememberScrollState()

    LaunchedEffect(screen.length) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(if (connected) Success else FgMuted, CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("My Terminal", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = FgMuted, maxLines = 1)
            }
            Box {
                IconButton(onClick = { historyOpen = true }, enabled = history.isNotEmpty()) {
                    Icon(Icons.Default.History, "Command history", tint = if (history.isEmpty()) FgMuted.copy(alpha = 0.4f) else FgMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                    history.forEach { cmd ->
                        DropdownMenuItem(
                            text = { Text(cmd, style = MonoStyle, maxLines = 1) },
                            onClick = { input = cmd; historyOpen = false },
                        )
                    }
                }
            }
            IconButton(onClick = { TerminalClient.clearScreen() }) {
                Icon(Icons.Default.Delete, "Clear", tint = FgMuted, modifier = Modifier.size(18.dp))
            }
        }

        if (settings.terminalUrl.isBlank()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No terminal URL set", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Run ttyd on your own machine and expose it with your own tunnel " +
                        "(ngrok, Cloudflare — whatever you use), then paste that URL in Settings. " +
                        "This is always your own — ChomuGiri never ships or shares a tunnel URL.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Surface(color = BgElevated, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("1. start the terminal", style = MaterialTheme.typography.labelSmall, color = FgMuted)
                        Text("ttyd -W -p 7681 bash", style = MonoStyle, color = Accent2)
                        Spacer(Modifier.height(8.dp))
                        Text("2. expose it", style = MaterialTheme.typography.labelSmall, color = FgMuted)
                        Text(
                            "ngrok http --url=<your-domain> 7681",
                            style = MonoStyle,
                            color = Accent2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Then paste that https:// URL in Settings. Both have to stay running " +
                                "while you use the terminal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FgMuted,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Open Settings") }
            }
            return@Column
        }

        SelectionContainer {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF07070B))
                    .verticalScroll(scroll)
                    .padding(12.dp)
            ) {
                Text(
                    screen.ifBlank { "Not connected yet. Tap Connect below." },
                    style = MonoStyle,
                    color = Color(0xFFD5F0DA),
                )
            }
        }

        Column(Modifier.padding(12.dp).navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                settings.terminalMacros.forEach { macro ->
                    Surface(
                        color = BgElevated, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { input = macro },
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(macro, style = MonoStyle, color = Accent2, maxLines = 1)
                            IconButton(
                                onClick = { vm.updateSettings { it.copy(terminalMacros = it.terminalMacros - macro) } },
                                modifier = Modifier.size(16.dp).padding(start = 6.dp),
                            ) { Icon(Icons.Default.Delete, "Remove macro", tint = FgMuted, modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
                Surface(
                    color = BgElevated, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable(enabled = input.isNotBlank()) {
                        vm.updateSettings { it.copy(terminalMacros = (it.terminalMacros + input).distinct()) }
                    },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = FgMuted, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save as macro", style = MaterialTheme.typography.labelSmall, color = FgMuted)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = connected,
                    placeholder = { Text("Type a command...", color = FgMuted) },
                    textStyle = MonoStyle,
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(alpha = 0.6f),
                        unfocusedBorderColor = BorderCol,
                        focusedContainerColor = BgElevated,
                        unfocusedContainerColor = BgElevated,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        TerminalClient.recordHistory(input)
                        TerminalClient.sendInput(input + "\n")
                        input = ""
                    },
                    enabled = connected,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Run", tint = Color.White) }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { if (connected) vm.disconnectTerminal() else vm.retryTerminal() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) BgElevated2 else Accent,
                ),
            ) { Text(if (connected) "Disconnect" else "Reconnect") }

            if (!connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Connects on its own at startup and keeps retrying. If it stays down, the " +
                        "tunnel or ttyd probably isn't running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
            }
        }
    }
}
