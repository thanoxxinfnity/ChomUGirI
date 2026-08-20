package com.chomugiri.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chomugiri.app.core.Artifact
import com.chomugiri.app.core.suggestedZipName
import com.chomugiri.app.core.writeArtifactZip
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.data.DeployUiState
import com.chomugiri.app.net.TerminalClient

private enum class ArtifactTab(val label: String) { CODE("Code"), APK("Android APK"), TERMINAL("Agent Log") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactScreen(vm: AppViewModel, artifact: Artifact, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(ArtifactTab.CODE) }
    var canvas by remember { mutableStateOf(false) }
    var selected by remember(artifact.id) { mutableStateOf(0) }
    val context = LocalContext.current

    val generating by vm.busy.collectAsState()
    val deployStates by vm.deployState.collectAsState()
    val deployState = deployStates[artifact.id]
    val deployDisabled = generating || deployState is DeployUiState.Deploying

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val msg = try {
                val n = writeArtifactZip(context, uri, artifact)
                "Saved $n file(s) as .zip"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        if (!canvas) {
            TopAppBar(
                title = {
                    Column {
                        Text(artifact.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text("${artifact.files.size} file(s)", style = MaterialTheme.typography.bodySmall, color = FgMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { zipLauncher.launch(suggestedZipName(artifact)) }) {
                        Icon(Icons.Default.Code, "Export .zip", tint = Accent2)
                    }
                    IconButton(
                        onClick = { vm.deployArtifact(artifact) },
                        enabled = !deployDisabled,
                    ) {
                        when (deployState) {
                            is DeployUiState.Deploying ->
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                            is DeployUiState.Success ->
                                Icon(Icons.Default.CloudDone, "Deployed", tint = Success)
                            is DeployUiState.Failed ->
                                Icon(Icons.Default.CloudOff, "Deploy failed", tint = Danger)
                            null ->
                                Icon(Icons.Default.Cloud, "Deploy", tint = if (deployDisabled) FgMuted else Accent)
                        }
                    }
                    IconButton(onClick = { canvas = true }) {
                        Icon(Icons.Default.Fullscreen, "Canvas")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
            )

            if (generating) {
                DeployBanner("Deploy is disabled while the swarm is still working.", FgMuted)
            } else when (val d = deployState) {
                is DeployUiState.Success -> DeployBanner(
                    d.url?.let { "Deployed — $it" } ?: "Deployed.",
                    Success,
                    onClick = d.url?.let { url -> { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                )
                is DeployUiState.Failed -> DeployBanner(d.message, Danger)
                else -> Unit
            }

            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = BgDark,
                contentColor = Accent,
            ) {
                ArtifactTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }

        when {
            canvas -> CanvasView(artifact, selected, { selected = it }) { canvas = false }
            tab == ArtifactTab.CODE -> CodeView(artifact, selected) { selected = it }
            tab == ArtifactTab.APK -> ApkView(vm, artifact)
            else -> AgentLogView(vm)
        }
    }
}

@Composable
private fun DeployBanner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.10f),
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
        )
    }
}

@Composable
private fun FileChips(artifact: Artifact, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        artifact.files.forEachIndexed { i, f ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.22f),
                    containerColor = BgElevated,
                ),
            )
        }
    }
}

@Composable
private fun CodeBody(code: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Box(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(code, style = MonoStyle, color = Color(0xFFE6E4F0))
        }
    }
}

@Composable
private fun CodeView(artifact: Artifact, selected: Int, onSelect: (Int) -> Unit) {
    val idx = selected.coerceIn(0, (artifact.files.size - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        FileChips(artifact, idx, onSelect)
        Text(
            artifact.files.getOrNull(idx)?.path.orEmpty(),
            Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FgMuted,
        )
        CodeBody(artifact.files.getOrNull(idx)?.content.orEmpty())
    }
}

@Composable
private fun CanvasView(artifact: Artifact, selected: Int, onSelect: (Int) -> Unit, onExit: () -> Unit) {
    val idx = selected.coerceIn(0, (artifact.files.size - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                artifact.files.getOrNull(idx)?.path.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = FgMuted,
                maxLines = 1,
            )
            IconButton(onClick = onExit) { Icon(Icons.Default.FullscreenExit, "Exit canvas") }
        }
        FileChips(artifact, idx, onSelect)
        CodeBody(artifact.files.getOrNull(idx)?.content.orEmpty())
    }
}

@Composable
private fun ApkView(vm: AppViewModel, artifact: Artifact) {
    val settings by vm.settings.collectAsState()
    val connected by TerminalClient.connected.collectAsState()
    val running by vm.agentRunning.collectAsState()

    val ready = connected && settings.agentTerminalEnabled

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Build a real APK", style = MaterialTheme.typography.titleMedium)
        Text(
            "An Android app cannot compile an APK inside itself — there is no JDK, Gradle or " +
                "Android SDK in an app sandbox. So this runs the build on your own machine, " +
                "through the terminal you connected. ChomuGirI copies the project over, then " +
                "runs the build commands there and shows you the real output.",
            style = MaterialTheme.typography.bodyMedium,
            color = FgMuted,
        )

        Surface(color = BgElevated, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your machine needs:", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    "ttyd running and exposed (your own tunnel)",
                    "node + npm",
                    "a JDK (17 or newer)",
                    "Android SDK with ANDROID_HOME set",
                ).forEach {
                    Text("•  $it", style = MaterialTheme.typography.bodySmall, color = FgMuted)
                }
            }
        }

        if (!connected) {
            StatusNote("Terminal not connected — open the Terminal tab and connect first.", Danger)
        } else if (!settings.agentTerminalEnabled) {
            StatusNote("Agent terminal access is off — turn it on in Settings.", Accent2)
        } else {
            StatusNote("Terminal connected and agent access is on.", Success)
        }

        Button(
            onClick = { vm.buildApkFromArtifact(artifact) },
            enabled = ready && !running,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Android, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Building..." else "Build APK on my machine")
        }

        Text(
            "Watch it work on the Agent Log tab.",
            style = MaterialTheme.typography.bodySmall,
            color = FgMuted,
        )
    }
}

@Composable
private fun StatusNote(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun AgentLogView(vm: AppViewModel) {
    val log by vm.agentLog.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(log.length) { scroll.animateScrollTo(scroll.maxValue) }

    SelectionContainer {
        Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp)) {
            Text(
                log.ifBlank { "Nothing yet — start a build from the Android APK tab." },
                style = MonoStyle,
                color = if (log.isBlank()) FgMuted else Color(0xFFCFE8D4),
            )
        }
    }
}
