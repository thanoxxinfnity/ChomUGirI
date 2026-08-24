package com.chomugiri.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.ChevronRight
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

    // Follow the stream only while the user is already at the bottom. Unconditionally scrolling
    // on every content change meant scrolling up to re-read something mid-generation yanked you
    // straight back down on the next token — you physically could not read your own history
    // while the model was still talking.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && atBottom) listState.animateScrollToItem(messages.size - 1)
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
                            MessageRow(
                                msg, artifacts, onOpenArtifact,
                                onBranch = { msgId -> activeId?.let { vm.branchConversation(it, msgId) } },
                                onPickOption = { optionText -> vm.send(optionText) },
                            )
                        }
                    }
                }
            }
        }

        Composer(
            busy = busy,
            onSend = { text, forcedIntent -> vm.send(text, forcedIntent) },
            onStop = { activeId?.let { vm.stop(it) } },
            latestArtifact = artifacts.maxByOrNull { it.createdAt },
            onOpenCanvas = onOpenArtifact,
            buildMode = settings.mode().label,
            onModeChange = { label -> vm.updateSettings { it.copy(buildMode = label) } },
            onSendWithRole = { t, role -> vm.send(t, forcedRole = role) },
            customModels = settings.customModels,
            onSendWithCustomModel = { t, id -> vm.send(t, forcedCustomModelId = id) },
        )
    }
}

private val OPTION_LINE = Regex("""(?im)^\s*Option\s+([A-C]):\s*(.+)$""")

/** Kimi's Option A/B/C lines, parsed into (letter, title) pairs for tap-to-pick chips. */
private fun parseOptions(content: String): List<Pair<String, String>> =
    OPTION_LINE.findAll(content).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

@Composable
private fun MessageRow(
    msg: Message,
    artifacts: List<Artifact>,
    onOpenArtifact: (String) -> Unit,
    onBranch: (String) -> Unit,
    onPickOption: (String) -> Unit,
) {
    val isUser = msg.role == "user"
    val haptics = rememberHaptics()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Also shown before the first step lands, so the live card is what you see from the
        // moment you hit send, rather than a bare "Thinking..." that later swaps for a card.
        if (!isUser && (msg.steps.isNotEmpty() || (msg.streaming && msg.error == null))) {
            ThinkingBubble(msg)
            Spacer(Modifier.height(6.dp))
        }

        if (!isUser && msg.fileActions.isNotEmpty()) {
            FileActionList(msg.fileActions) { msg.artifactId?.let(onOpenArtifact) }
            Spacer(Modifier.height(6.dp))
        }

        if (msg.content.isNotBlank()) {
            val contentVisible = remember(msg.id) {
                androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = contentVisible,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 6 },
            ) {
                Column(
                    Modifier.widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    parseMessageParts(msg.content).forEach { part ->
                        when (part) {
                            is MessagePart.Code -> CodeBlock(part.lang, part.code)
                            is MessagePart.Prose -> SelectionContainer {
                                // Asymmetric corners: the corner nearest the speaker is tucked in,
                                // which is what makes a bubble read as pointing at its author
                                // rather than floating between the two columns.
                                val bubbleShape = if (isUser) {
                                    RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
                                } else {
                                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                                }
                                Surface(
                                    color = if (isUser) Accent.copy(alpha = 0.13f) else BgElevated,
                                    shape = bubbleShape,
                                    modifier = if (isUser) {
                                        Modifier.hairlineAccent(bubbleShape, alpha = 0.35f)
                                    } else {
                                        Modifier.hairline(bubbleShape)
                                    },
                                ) {
                                    // The user's own message is shown exactly as typed — running
                                    // their text through a markdown parser would silently eat
                                    // their asterisks and underscores. Only the assistant's reply,
                                    // which is genuinely written in markdown, gets rendered.
                                    if (isUser) {
                                        Text(
                                            part.text.trim(),
                                            Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    } else {
                                        MarkdownView(
                                            part.text.trim(),
                                            Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isUser && !msg.streaming && msg.artifactId == null) {
            val options = remember(msg.content) { parseOptions(msg.content) }
            if (options.isNotEmpty()) {
                val optionsVisible = remember(msg.id) {
                    androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = optionsVisible,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 100)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260, delayMillis = 100)) { it / 4 },
                ) {
                    // Swipeable pills rather than a stack of full-width rows: options are short
                    // and comparable, so side-by-side lets you read them against each other with
                    // a thumb flick instead of pushing the reply off screen.
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (letter, title) ->
                            val pillShape = RoundedCornerShape(20.dp)
                            Surface(
                                color = BgElevated,
                                shape = pillShape,
                                modifier = Modifier
                                    .widthIn(max = 260.dp)
                                    .hairlineAccent(pillShape, alpha = 0.45f)
                                    .clickable { haptics.tap(); onPickOption("Option $letter: $title") },
                            ) {
                                Row(
                                    Modifier.padding(start = 10.dp, end = 15.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(color = Accent.copy(alpha = 0.20f), shape = CircleShape) {
                                        Text(
                                            letter,
                                            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Accent,
                                        )
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
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

        msg.attachment?.let { file ->
            Spacer(Modifier.height(8.dp))
            AttachedFileRow(file)
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
    val haptics = rememberHaptics()
    val artShape = RoundedCornerShape(16.dp)
    Surface(
        color = BgElevated,
        shape = artShape,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .hairlineAccent(artShape, alpha = 0.4f)
            .clickable { haptics.tap(); onOpen() },
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
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

/**
 * A real finished file the assistant is handing over — today, an APK it just built on the user's
 * own machine and pulled back. Tapping installs it; the share button saves it anywhere.
 *
 * The cache directory Android hands us can be cleared by the system at any time, so the row
 * checks the file still exists rather than assuming a path recorded days ago is still valid — a
 * tap that opens an installer for a file that is gone is the worst version of this.
 */
@Composable
private fun AttachedFileRow(file: com.chomugiri.app.core.MessageFile) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    val present = remember(file.path) { file.exists() }
    val shape = RoundedCornerShape(16.dp)

    fun uri(): android.net.Uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", java.io.File(file.path),
    )

    fun open() {
        if (!present) return
        haptics.commit()
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri(), "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No installer, or install-from-unknown-sources blocked. Sharing still works, and is
            // the honest fallback — never fail silently on a tap the user clearly meant.
            android.widget.Toast.makeText(
                context,
                "Couldn't open the installer. Use the share button to save the APK instead.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun share() {
        haptics.tap()
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(android.content.Intent.EXTRA_STREAM, uri())
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(i, "Save or send the APK"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Nothing on this phone can take that file.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        color = BgElevated,
        shape = shape,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .then(if (present) Modifier.hairlineAccent(shape, alpha = 0.5f) else Modifier.hairline(shape))
            .clickable(enabled = present) { open() },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (present) Accent.copy(alpha = 0.16f) else BgElevated2,
                shape = RoundedCornerShape(11.dp),
            ) {
                Icon(
                    Icons.Default.Android,
                    null,
                    tint = if (present) Accent else FgMuted,
                    modifier = Modifier.padding(8.dp).size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = FgPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    if (present) "${file.prettySize()} · tap to install" else "No longer on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (present) FgMuted else Danger,
                )
            }
            if (present) {
                IconButton(onClick = { share() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.IosShare, "Save the APK", tint = FgMuted, modifier = Modifier.size(18.dp))
                }
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

/**
 * The AI's reasoning as a live, animated timeline instead of a pill you had to tap to open a
 * dialog. Steps stream in one by one, each sliding in against a connector rail; the one in flight
 * carries a breathing dot and shimmering text, finished ones settle into a muted check. It stays
 * expanded while the model is actually working — that is the part worth watching — and folds
 * itself down to a one-line summary once the answer lands, so finished chats stay readable.
 */
@Composable
private fun ThinkingBubble(msg: Message) {
    val haptics = rememberHaptics()
    val active = msg.streaming && msg.error == null
    val failed = msg.error != null

    // null = follow the run (open while thinking, closed once done). A tap pins it either way.
    var pinned by remember(msg.id) { mutableStateOf<Boolean?>(null) }
    val expanded = pinned ?: active

    val tint = when {
        failed -> Danger
        active -> Accent
        else -> Success
    }

    var elapsed by remember(msg.id) { mutableStateOf(0L) }
    LaunchedEffect(msg.id, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - msg.createdAt).coerceAtLeast(0L) / 1000
            delay(1000)
        }
    }

    val headline = when {
        failed -> "Build failed"
        active -> msg.steps.lastOrNull()?.text?.trimEnd('.') ?: "Thinking"
        else -> "Thought for ${msg.steps.size} step${if (msg.steps.size == 1) "" else "s"}"
    }

    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        color = BgElevated,
        shape = cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.hairlineAccent(cardShape) else Modifier.hairline(cardShape)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { haptics.toggle(); pinned = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingIcon(
                    icon = if (failed) Icons.Default.ErrorOutline
                    else if (active) Icons.Default.AutoAwesome
                    else Icons.Default.CheckCircle,
                    tint = tint,
                    animate = active,
                )
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f)) {
                    if (active) ShimmerText(headline) else Text(
                        headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) Danger else FgMuted,
                        maxLines = 1,
                    )
                }
                if (active && elapsed > 0) {
                    Text(
                        "${elapsed}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = FgMuted,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Hide steps" else "Show steps",
                    tint = FgMuted,
                    modifier = Modifier.size(17.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 13.dp, end = 13.dp, bottom = 11.dp),
                ) {
                    msg.steps.forEachIndexed { i, step ->
                        val isLast = i == msg.steps.lastIndex
                        // Only the newest step animates in; replaying every step on each
                        // recomposition would make the whole list twitch as one streams.
                        val appear = remember(msg.id, i) {
                            androidx.compose.animation.core.MutableTransitionState(false)
                                .apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = appear,
                            enter = fadeIn(tween(260)) +
                                slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 5 },
                        ) {
                            StepRow(
                                text = step.text,
                                done = step.done,
                                running = active && isLast && !step.done,
                                showRail = !isLast || failed,
                                tint = tint,
                            )
                        }
                    }
                    msg.error?.let { err ->
                        StepRow(text = err, done = false, running = false, showRail = false, tint = Danger, isError = true)
                    }
                }
            }
        }
    }
}

/** One row of the reasoning timeline: rail dot, connector down to the next step, and the text. */
@Composable
private fun StepRow(
    text: String,
    done: Boolean,
    running: Boolean,
    showRail: Boolean,
    tint: Color,
    isError: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(5.dp))
            when {
                isError -> Icon(Icons.Default.ErrorOutline, null, tint = Danger, modifier = Modifier.size(11.dp))
                done -> Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(11.dp))
                running -> BreathingDot(tint)
                else -> Box(
                    Modifier.size(7.dp).clip(CircleShape).background(FgMuted.copy(alpha = 0.45f))
                )
            }
            if (showRail) {
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .heightIn(min = 8.dp)
                        .background(BorderCol)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (running) {
                ShimmerBody(text)
            } else {
                Text(
                    text,
                    style = MonoStyle,
                    color = if (isError) Danger else FgMuted,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
            }
        }
    }
}

/** Monospace twin of ShimmerText, so a running step's own line breathes with it. */
@Composable
private fun ShimmerBody(text: String) {
    val transition = rememberInfiniteTransition(label = "stepShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "stepAlpha",
    )
    Text(
        text,
        style = MonoStyle,
        color = FgPrimary.copy(alpha = alpha),
        modifier = Modifier.padding(bottom = 9.dp),
    )
}

/** The in-flight marker: a dot that swells and fades, with a halo pulsing out behind it. */
@Composable
private fun BreathingDot(tint: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(11.dp)) {
        Box(
            Modifier
                .size(11.dp)
                .graphicsLayer {
                    val s = 0.5f + pulse * 0.9f
                    scaleX = s; scaleY = s; alpha = (1f - pulse) * 0.55f
                }
                .clip(CircleShape)
                .background(tint)
        )
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
    }
}

/** Header glyph that gently breathes while a run is live, and sits still once it is done. */
@Composable
private fun PulsingIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    animate: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "icon")
    val scale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "iconScale",
    )
    Icon(
        icon, null, tint = tint,
        modifier = Modifier.size(15.dp).scale(if (animate) scale else 1f),
    )
}

/**
 * What the swarm actually did to the project, one compact row per file, landing in the chat as
 * the work happens rather than staying buried in the project panel. Each row animates in on
 * arrival and opens the project when tapped.
 */
@Composable
private fun FileActionList(actions: List<com.chomugiri.app.core.FileAction>, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { a ->
            val appear = remember(a.path) {
                androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
            }
            AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(tween(240)) +
                    slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 6 },
            ) {
                FileActionRow(a, onOpen)
            }
        }
    }
}

@Composable
private fun FileActionRow(action: com.chomugiri.app.core.FileAction, onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    Surface(
        color = BgElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderCol, RoundedCornerShape(10.dp))
            .clickable { haptics.tap(); onOpen() },
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                action.verb,
                style = MaterialTheme.typography.labelSmall,
                color = FgMuted,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                action.path.substringAfterLast('/'),
                style = MonoStyle,
                color = FgPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            // Always both numbers, even a zero: "+110 -0" reads as a measured diff, while a
            // bare "+110" leaves you wondering whether anything was removed.
            Text("+${action.added}", style = MonoStyle, color = Success)
            Spacer(Modifier.width(5.dp))
            Text("-${action.removed}", style = MonoStyle, color = Danger)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ChevronRight, "Open project",
                tint = FgMuted, modifier = Modifier.size(15.dp),
            )
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
    "Auditor" to "auditor",
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
        Logomark(size = 56.dp, breathe = true)
        Spacer(Modifier.height(18.dp))
        Text(
            "What are we building today?",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Describe it and the swarm builds, audits and ships it — Code, Canvas, live preview, " +
                ".zip export, one-tap deploy and a real APK build all land on the project.",
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
            val suggestShape = RoundedCornerShape(20.dp)
            Surface(
                color = BgElevated,
                shape = suggestShape,
                modifier = Modifier
                    .hairline(suggestShape)
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
    onSend: (String, com.chomugiri.app.core.Intent?) -> Unit,
    onStop: () -> Unit,
    latestArtifact: Artifact? = null,
    onOpenCanvas: (String) -> Unit = {},
    buildMode: String = "BALANCED",
    onModeChange: (String) -> Unit = {},
    onSendWithRole: (String, com.chomugiri.app.core.RoleKey) -> Unit = { _, _ -> },
    customModels: List<com.chomugiri.app.core.CustomModel> = emptyList(),
    onSendWithCustomModel: (String, String) -> Unit = { _, _ -> },
) {
    val haptics = rememberHaptics()
    val sendTransition = rememberInfiniteTransition(label = "send")
    val sendPulse by sendTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sendPulse",
    )
    var text by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    // Explicit tools the user opts into from the "+" menu — never auto-guessed by the router.
    var pickedIntent by remember { mutableStateOf<com.chomugiri.app.core.Intent?>(null) }
    var attachedName by remember { mutableStateOf<String?>(null) }
    var forcedRole by remember { mutableStateOf<com.chomugiri.app.core.RoleKey?>(null) }
    var forcedCustomModel by remember { mutableStateOf<com.chomugiri.app.core.CustomModel?>(null) }
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
            // A gradient fade rather than a hard rule. A 1px divider across the full width cuts
            // the screen into two unrelated halves; a fade reads as the composer sitting in front
            // of the thread, which is what it actually is.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(BorderCol.copy(alpha = 0f), BorderCol, BorderCol.copy(alpha = 0f)),
                        )
                    )
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.chomugiri.app.core.BUILD_MODES.forEach { m ->
                    val selected = m.label == buildMode
                    val chipShape = RoundedCornerShape(11.dp)
                    Surface(
                        color = if (selected) Accent.copy(alpha = 0.16f) else BgElevated,
                        shape = chipShape,
                        modifier = Modifier
                            .then(
                                if (selected) Modifier.hairlineAccent(chipShape, alpha = 0.6f)
                                else Modifier.hairline(chipShape)
                            )
                            .clickable { haptics.select(); onModeChange(m.label) },
                    ) {
                        Text(
                            m.label,
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Accent else FgMuted,
                        )
                    }
                }
            }

            com.chomugiri.app.core.BUILD_MODES.firstOrNull { it.label == buildMode }?.let { m ->
                Text(
                    m.description,
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
                    val forcedLabel = forcedCustomModel?.let { it.name.ifBlank { it.model } } ?: forcedRole?.let { com.chomugiri.app.core.ROLE_LABELS[it] }
                    Surface(
                        color = if (forcedLabel != null) Accent.copy(alpha = 0.18f) else BgElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { roleMenuOpen = true },
                    ) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                forcedLabel ?: "Auto",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (forcedLabel != null) Accent else FgMuted,
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = if (forcedLabel != null) Accent else FgMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                    DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto (router decides)") },
                            onClick = { roleMenuOpen = false; forcedRole = null; forcedCustomModel = null },
                        )
                        com.chomugiri.app.core.ROLE_ORDER.forEach { role ->
                            DropdownMenuItem(
                                text = { Text(com.chomugiri.app.core.ROLE_LABELS[role] ?: role.name) },
                                onClick = { haptics.select(); roleMenuOpen = false; forcedRole = role; forcedCustomModel = null },
                            )
                        }
                        if (customModels.isNotEmpty()) {
                            HorizontalDivider()
                            customModels.forEach { cm ->
                                DropdownMenuItem(
                                    text = { Text(cm.name.ifBlank { cm.model.ifBlank { "Untitled model" } }) },
                                    onClick = { haptics.select(); roleMenuOpen = false; forcedCustomModel = cm; forcedRole = null },
                                )
                            }
                        }
                    }
                }
            }

            if (pickedIntent != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(color = Accent2.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                        Row(
                            Modifier
                                .clickable { pickedIntent = null }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (pickedIntent) {
                                    com.chomugiri.app.core.Intent.PPTX -> "Create a PPT"
                                    com.chomugiri.app.core.Intent.VIDEO -> "Generate a video"
                                    else -> "Deep Research"
                                },
                                style = MaterialTheme.typography.labelSmall, color = Accent2,
                            )
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
                            text = { Text(if (pickedIntent == com.chomugiri.app.core.Intent.RESEARCH) "Deep Research (on)" else "Deep Research") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.TravelExplore, null,
                                    tint = if (pickedIntent == com.chomugiri.app.core.Intent.RESEARCH) Accent else FgMuted,
                                )
                            },
                            onClick = {
                                toolsOpen = false
                                pickedIntent = if (pickedIntent == com.chomugiri.app.core.Intent.RESEARCH) null else com.chomugiri.app.core.Intent.RESEARCH
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (pickedIntent == com.chomugiri.app.core.Intent.PPTX) "Create a PPT (on)" else "Create a PPT (with images)") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Slideshow, null,
                                    tint = if (pickedIntent == com.chomugiri.app.core.Intent.PPTX) Accent else FgMuted,
                                )
                            },
                            onClick = {
                                toolsOpen = false
                                pickedIntent = if (pickedIntent == com.chomugiri.app.core.Intent.PPTX) null else com.chomugiri.app.core.Intent.PPTX
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (pickedIntent == com.chomugiri.app.core.Intent.VIDEO) "Generate a video (on)" else "Generate a video (Hugging Face)") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Movie, null,
                                    tint = if (pickedIntent == com.chomugiri.app.core.Intent.VIDEO) Accent else FgMuted,
                                )
                            },
                            onClick = {
                                toolsOpen = false
                                pickedIntent = if (pickedIntent == com.chomugiri.app.core.Intent.VIDEO) null else com.chomugiri.app.core.Intent.VIDEO
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
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        // Only the focused field carries accent. An always-accented input makes
                        // every screen look "active" and leaves focus with nothing left to signal.
                        focusedBorderColor = Accent.copy(alpha = 0.65f),
                        unfocusedBorderColor = BorderCol,
                        focusedContainerColor = BgElevated,
                        unfocusedContainerColor = BgElevated,
                        cursorColor = Accent,
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
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
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
                    forcedCustomModel?.let { onSendWithCustomModel(t, it.id) }
                        ?: forcedRole?.let { onSendWithRole(t, it) }
                        ?: onSend(t, pickedIntent)
                    text = ""; pickedIntent = null; attachedName = null
                }
                FilledIconButton(
                    onClick = { if (busy) { haptics.tap(); onStop() } else { haptics.commit(); doSend() } },
                    // Breathes only when there is something to send. A control that pulses while
                    // idle is just noise; pulsing exactly when it becomes actionable reads as the
                    // button waking up, and doubles as a hint that the message is ready to go.
                    modifier = Modifier.size(48.dp).scale(if (text.isNotBlank() && !busy) sendPulse else 1f),
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
