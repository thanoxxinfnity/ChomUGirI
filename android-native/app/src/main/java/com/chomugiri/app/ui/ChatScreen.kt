package com.chomugiri.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chomugiri.app.core.Artifact
import com.chomugiri.app.core.Message
import com.chomugiri.app.data.AppViewModel

@Composable
fun ChatScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenArtifact: (String) -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeConversationId.collectAsState()
    val busy by vm.busy.collectAsState()
    val settings by vm.settings.collectAsState()
    val artifacts by vm.artifacts.collectAsState()

    val messages = conversations.firstOrNull { it.id == activeId }?.messages ?: emptyList()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    hasApiKey = settings.provider(com.chomugiri.app.core.RoleKey.FAST).apiKey.isNotBlank(),
                    onOpenSettings = onOpenSettings,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageRow(msg, artifacts, onOpenArtifact)
                    }
                }
            }
        }

        Composer(
            busy = busy,
            onSend = { vm.send(it) },
            onStop = { vm.stop() },
        )
    }
}

@Composable
private fun MessageRow(msg: Message, artifacts: List<Artifact>, onOpenArtifact: (String) -> Unit) {
    val isUser = msg.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && msg.steps.isNotEmpty()) {
            ThinkingBubble(msg)
            Spacer(Modifier.height(6.dp))
        }

        if (msg.content.isNotBlank()) {
            SelectionContainer {
                Surface(
                    color = if (isUser) Accent.copy(alpha = 0.16f) else BgElevated,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.widthIn(max = 560.dp),
                ) {
                    Text(
                        msg.content,
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else if (!isUser && msg.streaming && msg.steps.isEmpty()) {
            ShimmerText("Thinking...")
        }

        msg.artifactId?.let { id ->
            val art = artifacts.firstOrNull { it.id == id }
            if (art != null) {
                Spacer(Modifier.height(8.dp))
                ArtifactCard(art) { onOpenArtifact(id) }
            }
        }

        msg.error?.let {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = Danger.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ArtifactCard(artifact: Artifact, onOpen: () -> Unit) {
    Surface(
        color = BgElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .widthIn(max = 560.dp)
            .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderOpen, null, tint = Accent2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(artifact.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${artifact.files.size} file(s) · tap to open Code, Canvas, .zip, APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = FgMuted,
                )
            }
        }
    }
}

/** Collapsed by default; the full step log opens in a dialog rather than cluttering the thread. */
@Composable
private fun ThinkingBubble(msg: Message) {
    var open by remember { mutableStateOf(false) }
    val active = msg.streaming && msg.error == null
    val label = when {
        msg.error != null -> "Build failed — tap for details"
        active -> msg.steps.lastOrNull()?.text ?: "Thinking..."
        else -> "Thought for a moment"
    }

    Surface(
        color = when {
            msg.error != null -> Danger.copy(alpha = 0.12f)
            active -> Accent.copy(alpha = 0.12f)
            else -> Success.copy(alpha = 0.10f)
        },
        shape = CircleShape,
        modifier = Modifier.clickable { open = true },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    msg.error != null -> Icons.Default.ErrorOutline
                    active -> Icons.Default.AutoAwesome
                    else -> Icons.Default.CheckCircle
                },
                null,
                tint = when {
                    msg.error != null -> Danger
                    active -> Accent
                    else -> Success
                },
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            if (active) ShimmerText(label) else Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (msg.error != null) Danger else Success,
            )
        }
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(color = BgElevated, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp).widthIn(max = 460.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { open = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Close", tint = FgMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        msg.steps.forEach { step ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (step.done) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                                    null,
                                    tint = if (step.done) Success else Accent,
                                    modifier = Modifier.size(13.dp).padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(step.text, style = MonoStyle, color = if (step.done) FgMuted else Color.White)
                            }
                        }
                        msg.error?.let {
                            Text(it, style = MonoStyle, color = Danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShimmerText(text: String) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = alpha),
        modifier = Modifier.alpha(alpha),
    )
}

@Composable
private fun EmptyState(hasApiKey: Boolean, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logomark(size = 52.dp)
        Spacer(Modifier.height(16.dp))
        Text("ChomuGirI", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Just talk and you get a fast reply. Ask for an app, site, or script and the full " +
                "swarm takes over automatically — then Code, Canvas, .zip export and a real APK " +
                "build show up on the project itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = FgMuted,
            modifier = Modifier.widthIn(max = 380.dp),
        )

        if (!hasApiKey) {
            Spacer(Modifier.height(18.dp))
            Surface(
                color = Accent2.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.clickable(onClick = onOpenSettings),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.VpnKey, null, tint = Accent2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("No API key yet — tap to add one", color = Accent2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }

    Surface(color = BgDark) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ChomuGirI...", color = FgMuted) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
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
                    if (busy) onStop() else {
                        val t = text.trim()
                        if (t.isNotEmpty()) { onSend(t); text = "" }
                    }
                },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    if (busy) "Stop" else "Send",
                    tint = Color.White,
                )
            }
        }
    }
}
