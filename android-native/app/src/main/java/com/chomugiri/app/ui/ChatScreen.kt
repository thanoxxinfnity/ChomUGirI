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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chomugiri.app.core.Artifact
import com.chomugiri.app.core.Message
import com.chomugiri.app.data.AppViewModel
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenArtifact: (String) -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeConversationId.collectAsState()
    val busyConversations by vm.busyConversations.collectAsState()
    val busy = activeId != null && activeId in busyConversations
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
                    onSuggestion = { vm.send(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Box(Modifier.animateItem()) {
                            MessageRow(msg, artifacts, onOpenArtifact) { msgId ->
                                activeId?.let { vm.branchConversation(it, msgId) }
                            }
                        }
                    }
                }
            }
        }

        Composer(
            busy = busy,
            onSend = { text, forceResearch ->
                vm.send(text, if (forceResearch) com.chomugiri.app.core.Intent.RESEARCH else null)
            },
            onStop = { activeId?.let { vm.stop(it) } },
            latestArtifact = artifacts.maxByOrNull { it.createdAt },
            onOpenCanvas = onOpenArtifact,
            auditLoops = settings.maxAuditLoops,
            onAuditLoopsChange = { n -> vm.updateSettings { it.copy(maxAuditLoops = n) } },
            onSendWithRole = { t, role -> vm.send(t, forcedRole = role) },
        )
    }
}

@Composable
private fun MessageRow(
    msg: Message,
    artifacts: List<Artifact>,
    onOpenArtifact: (String) -> Unit,
    onBranch: (String) -> Unit,
) {
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
            Column(
                Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                parseMessageParts(msg.content).forEach { part ->
                    when (part) {
                        is MessagePart.Code -> CodeBlock(part.lang, part.code)
                        is MessagePart.Prose -> SelectionContainer {
                            Surface(
                                color = if (isUser) Accent.copy(alpha = 0.16f) else BgElevated,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(
                                    part.text.trim(),
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        } else if (!isUser && msg.streaming && msg.steps.isEmpty()) {
            ShimmerText("Thinking...")
        }

        if (!isUser && !msg.streaming && msg.content.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                msg.modelUsed?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = FgMuted)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "Branch from here",
                    style = MaterialTheme.typography.labelSmall,
                    color = FgMuted,
                    modifier = Modifier.clickable { onBranch(msg.id) },
                )
            }
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

// ---------------------------------------------------------------------------------------------
// Message content: prose vs. fenced code blocks, each rendered distinctly
// ---------------------------------------------------------------------------------------------

private sealed class MessagePart {
    data class Prose(val text: String) : MessagePart()
    data class Code(val lang: String, val code: String) : MessagePart()
}

private val CODE_FENCE = Regex("```([a-zA-Z0-9_+-]*)\\n?([\\s\\S]*?)```")

private fun parseMessageParts(content: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    var last = 0
    for (m in CODE_FENCE.findAll(content)) {
        if (m.range.first > last) {
            val prose = content.substring(last, m.range.first)
            if (prose.isNotBlank()) parts += MessagePart.Prose(prose)
        }
        parts += MessagePart.Code(m.groupValues[1], m.groupValues[2].trim('\n'))
        last = m.range.last + 1
    }
    if (last < content.length) {
        val prose = content.substring(last)
        if (prose.isNotBlank()) parts += MessagePart.Prose(prose)
    }
    if (parts.isEmpty() && content.isNotBlank()) parts += MessagePart.Prose(content)
    return parts
}

/**
 * A code block always keeps this dark, terminal-style look regardless of the app's light/dark
 * theme — the same way code blocks stay dark in most chat UIs even on a light page.
 */
@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Surface(
        color = Color(0xFF121216),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF26262F), RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.ifBlank { "code" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8D8B9C),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        "Copy code",
                        tint = if (copied) Color(0xFF5FD9A4) else Color(0xFF8D8B9C),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF26262F))
            SelectionContainer {
                Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(highlightAnnotated(code), style = MonoStyle, color = Color(0xFFE6E4F0))
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
/** Cycles "." -> ".." -> "..." -> "" while genuinely still waiting on a real step. */
@Composable
private fun animatedDots(): String {
    var n by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(450)
            n = (n % 3) + 1
        }
    }
    return ".".repeat(n)
}

@Composable
private fun ThinkingBubble(msg: Message) {
    var open by remember { mutableStateOf(false) }
    val active = msg.streaming && msg.error == null
    val dots = if (active) animatedDots() else ""
    val label = when {
        msg.error != null -> "Build failed — tap for details"
        active -> (msg.steps.lastOrNull()?.text ?: "Thinking").trimEnd('.') + dots
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
                    active -> Icons.Default.Psychology
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
                        Icon(Icons.Default.Psychology, null, tint = Accent, modifier = Modifier.size(16.dp))
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
                                    if (step.done) Icons.Default.CheckCircle else Icons.Default.Psychology,
                                    null,
                                    tint = if (step.done) Success else Accent,
                                    modifier = Modifier.size(13.dp).padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(step.text, style = MonoStyle, color = if (step.done) FgMuted else FgPrimary)
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
        color = FgPrimary.copy(alpha = alpha),
        modifier = Modifier.alpha(alpha),
    )
}

private val SUGGESTIONS = listOf(
    "Build a todo app",
    "Make a portfolio site",
    "What's the capital of France?",
    "Research the latest phone launches",
)

private val ROUTER_STAGES = listOf(
    "Fast Chat" to "casual talk",
    "Kimi K3" to "coder",
    "GLM 5.2" to "auditor",
    "DeepSeek R1" to "fallback",
    "Nemotron" to "safety net",
)

@Composable
private fun EmptyState(
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logomark(size = 56.dp)
        Spacer(Modifier.height(18.dp))
        Text("ChomuGiri", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Just talk and you get a fast reply. Ask for an app, site, or script and the full " +
                "swarm takes over automatically — then Code, Canvas, .zip export and a real APK " +
                "build show up on the project itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = FgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 380.dp),
        )

        if (!hasApiKey) {
            Spacer(Modifier.height(16.dp))
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

        Spacer(Modifier.height(28.dp))

        FlowRowSuggestions(hasApiKey, onSuggestion)

        Spacer(Modifier.height(28.dp))

        Text(
            "AUTOMATIC ROUTER",
            style = MaterialTheme.typography.labelSmall,
            color = FgMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ROUTER_STAGES.forEachIndexed { i, (label, note) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgElevated.copy(alpha = 0.7f))
                        .border(1.dp, BorderCol, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(label, style = MonoStyle, color = FgPrimary, fontSize = 11.sp)
                    Text(note, style = MaterialTheme.typography.labelSmall, color = FgMuted, fontSize = 10.sp)
                }
                if (i < ROUTER_STAGES.lastIndex) {
                    Box(Modifier.width(14.dp).height(1.dp).background(BorderCol))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSuggestions(hasApiKey: Boolean, onSuggestion: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        SUGGESTIONS.forEach { prompt ->
            Surface(
                color = BgElevated,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, BorderCol, RoundedCornerShape(20.dp))
                    .clickable(enabled = hasApiKey) { onSuggestion(prompt) },
            ) {
                Text(
                    prompt,
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasApiKey) FgPrimary else FgMuted,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    busy: Boolean,
    onSend: (String, Boolean) -> Unit,
    onStop: () -> Unit,
    latestArtifact: Artifact? = null,
    onOpenCanvas: (String) -> Unit = {},
    auditLoops: Int = 2,
    onAuditLoopsChange: (Int) -> Unit = {},
    onSendWithRole: (String, com.chomugiri.app.core.RoleKey) -> Unit = { _, _ -> },
) {
    var text by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var researchMode by remember { mutableStateOf(false) }
    var attachedName by remember { mutableStateOf<String?>(null) }
    var forcedRole by remember { mutableStateOf<com.chomugiri.app.core.RoleKey?>(null) }
    var roleMenuOpen by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = com.chomugiri.app.core.queryDisplayName(context, uri) ?: "file"
        val content = com.chomugiri.app.core.readTextFile(context, uri, maxBytes = 60_000)
        if (content == null) {
            android.widget.Toast.makeText(context, "Couldn't read that as text.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            attachedName = name
            val prefix = if (text.isBlank()) "" else "\n\n"
            text += "$prefix### $name\n```\n$content\n```\n"
        }
    }

    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = if (text.isBlank()) spoken else "$text $spoken"
        }
    }

    val composerBg by androidx.compose.animation.animateColorAsState(BgDark, androidx.compose.animation.core.tween(200), label = "bg")
    Surface(color = composerBg) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.chomugiri.app.core.POWER_TIERS.forEach { tier ->
                    val selected = tier.auditLoops == auditLoops
                    Surface(
                        color = if (selected) Accent.copy(alpha = 0.22f) else BgElevated,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (selected) Accent.copy(alpha = 0.7f) else BorderCol,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { onAuditLoopsChange(tier.auditLoops) },
                    ) {
                        Text(
                            tier.label,
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MonoStyle,
                            color = if (selected) FgPrimary else FgMuted,
                        )
                    }
                }
            }

            com.chomugiri.app.core.POWER_TIERS.firstOrNull { it.auditLoops == auditLoops }?.let { tier ->
                Text(
                    tier.description,
                    Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = FgMuted,
                    maxLines = 2,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        color = if (forcedRole != null) Accent.copy(alpha = 0.18f) else BgElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { roleMenuOpen = true },
                    ) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                forcedRole?.let { com.chomugiri.app.core.ROLE_LABELS[it] } ?: "Auto",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (forcedRole != null) Accent else FgMuted,
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = if (forcedRole != null) Accent else FgMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                    DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto (router decides)") },
                            onClick = { roleMenuOpen = false; forcedRole = null },
                        )
                        com.chomugiri.app.core.ROLE_ORDER.forEach { role ->
                            DropdownMenuItem(
                                text = { Text(com.chomugiri.app.core.ROLE_LABELS[role] ?: role.name) },
                                onClick = { roleMenuOpen = false; forcedRole = role },
                            )
                        }
                    }
                }
            }

            if (researchMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(color = Accent2.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                        Row(
                            Modifier
                                .clickable { researchMode = false }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Deep Research", style = MaterialTheme.typography.labelSmall, color = Accent2)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Close, "Cancel", tint = Accent2, modifier = Modifier.size(12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(
                        onClick = { toolsOpen = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Default.Add, "Tools", tint = FgMuted)
                    }
                    DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open Canvas") },
                            leadingIcon = { Icon(Icons.Default.Fullscreen, null, tint = Accent2) },
                            enabled = latestArtifact != null,
                            onClick = {
                                toolsOpen = false
                                latestArtifact?.let { onOpenCanvas(it.id) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (researchMode) "Deep Research (on)" else "Deep Research") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.TravelExplore, null,
                                    tint = if (researchMode) Accent else FgMuted,
                                )
                            },
                            onClick = {
                                toolsOpen = false
                                researchMode = !researchMode
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Attach a text/code file") },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null, tint = FgMuted) },
                            onClick = {
                                toolsOpen = false
                                filePicker.launch("text/*")
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message ChomuGiri...", color = FgMuted) },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(alpha = 0.6f),
                        unfocusedBorderColor = BorderCol,
                        focusedContainerColor = BgElevated,
                        unfocusedContainerColor = BgElevated,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak now")
                        }
                        try {
                            speechLauncher.launch(intent)
                        } catch (e: Exception) {
                            // Covers no voice-input app (ActivityNotFoundException) and any other
                            // launch failure alike — this must never crash the composer.
                            android.widget.Toast.makeText(context, "Voice input isn't available on this device.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Mic, "Voice input", tint = FgMuted)
                }
                Spacer(Modifier.width(4.dp))
                fun doSend() {
                    val t = text.trim()
                    if (t.isEmpty()) return
                    forcedRole?.let { onSendWithRole(t, it) } ?: onSend(t, researchMode)
                    text = ""; researchMode = false; attachedName = null
                }
                FilledIconButton(
                    onClick = { if (busy) onStop() else doSend() },
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
}
