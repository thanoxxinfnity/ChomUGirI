package com.chomugiri.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chomugiri.app.core.*
import com.chomugiri.app.data.AppViewModel
import com.chomugiri.app.data.DeployUiState
import com.chomugiri.app.net.TerminalClient

private enum class ArtifactTab(val label: String) {
    CODE("Code"), RUN("Run"), APK("Android APK"), TERMINAL("Agent Log")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactScreen(vm: AppViewModel, artifact: Artifact, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(ArtifactTab.CODE) }
    var canvas by remember { mutableStateOf(false) }
    var selectedPath by remember(artifact.id) { mutableStateOf(artifact.files.firstOrNull()?.path) }
    var renamingProject by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val busyConversations by vm.busyConversations.collectAsState()
    val generating = busyConversations.isNotEmpty()
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

    if (renamingProject) {
        TextPromptDialog(
            title = "Rename project",
            initial = artifact.title,
            confirmLabel = "Rename",
            onDismiss = { renamingProject = false },
            onConfirm = { vm.renameArtifact(artifact.id, it); renamingProject = false },
        )
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        if (!canvas) {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { renamingProject = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(artifact.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Default.Edit, null, tint = FgMuted, modifier = Modifier.padding(start = 6.dp).size(13.dp))
                        }
                        Text("${artifact.files.size} file(s)", style = MaterialTheme.typography.bodySmall, color = FgMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { zipLauncher.launch(suggestedZipName(artifact)) }) {
                        Icon(Icons.Default.Download, "Export .zip", tint = Accent2)
                    }
                    IconButton(onClick = { shareArtifactZip(context, artifact) }) {
                        Icon(Icons.Default.Share, "Share .zip")
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
            canvas -> CanvasView(vm, artifact, selectedPath, { selectedPath = it }) { canvas = false }
            tab == ArtifactTab.CODE -> CodeView(vm, artifact, selectedPath, { selectedPath = it })
            tab == ArtifactTab.RUN -> RunView(artifact)
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

// ---------------------------------------------------------------------------------------------
// File tree
// ---------------------------------------------------------------------------------------------

private data class TreeRow(val depth: Int, val name: String, val fullPath: String?, val isFile: Boolean)

/** Flattens paths into a visible-row list, respecting which folders are currently expanded. */
private fun flattenTree(paths: List<String>, expanded: Set<String>): List<TreeRow> {
    data class Node(val name: String, var full: String?, val children: LinkedHashMap<String, Node> = LinkedHashMap())
    val root = Node("", null)
    for (p in paths) {
        var cur = root
        val segments = p.split('/').filter { it.isNotBlank() }
        segments.forEachIndexed { i, seg ->
            val isLast = i == segments.lastIndex
            cur = cur.children.getOrPut(seg) { Node(seg, if (isLast) p else null) }
        }
    }
    val out = mutableListOf<TreeRow>()
    fun walk(node: Node, depth: Int, prefix: String) {
        node.children.values.sortedWith(compareBy({ it.full == null && it.children.isEmpty() }, { it.name })).forEach { child ->
            val childPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val isFile = child.full != null
            out += TreeRow(depth, child.name, child.full, isFile)
            if (!isFile && childPath in expanded) walk(child, depth + 1, childPath)
        }
    }
    walk(root, 0, "")
    return out
}

@Composable
private fun FileTree(
    artifact: Artifact,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    regenerating: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(artifact.id) {
        mutableStateOf(artifact.files.map { it.path.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.toSet())
    }
    val rows = remember(artifact.files, expanded) { flattenTree(artifact.files.map { it.path }, expanded) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier.heightIn(max = 220.dp)) {
        items(rows, key = { it.fullPath ?: "${it.depth}:${it.name}" }) { row ->
            val folderPath = row.fullPath?.substringBeforeLast('/', "") ?: row.name
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (row.isFile) row.fullPath?.let(onSelect)
                        else expanded = if (folderPath in expanded) expanded - folderPath else expanded + folderPath
                    }
                    .padding(start = (12 + row.depth * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (row.isFile) Icons.Default.InsertDriveFile else if (folderPath in expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    null,
                    tint = if (row.isFile && row.fullPath == selectedPath) Accent else FgMuted,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    row.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isFile && row.fullPath == selectedPath) FgPrimary else FgMuted,
                    maxLines = 1,
                )
                if (row.isFile && row.fullPath == regenerating) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Accent)
                } else if (row.isFile) {
                    Box {
                        IconButton(onClick = { menuFor = row.fullPath }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, "File options", tint = FgMuted, modifier = Modifier.size(15.dp))
                        }
                        DropdownMenu(expanded = menuFor == row.fullPath, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Regenerate") }, onClick = { menuFor = null; row.fullPath?.let(onRegenerate) })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; row.fullPath?.let(onRename) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; row.fullPath?.let(onDelete) })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Code tab
// ---------------------------------------------------------------------------------------------

@Composable
private fun CodeView(vm: AppViewModel, artifact: Artifact, selectedPath: String?, onSelect: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val regenerating by vm.regeneratingFile.collectAsState()

    val current = artifact.files.firstOrNull { it.path == selectedPath } ?: artifact.files.firstOrNull()
    var draft by remember(current?.path, current?.content) { mutableStateOf(current?.content.orEmpty()) }
    val dirty = draft != (current?.content ?: "")

    var addingFile by remember { mutableStateOf(false) }
    var renamingPath by remember { mutableStateOf<String?>(null) }
    var regenTarget by remember { mutableStateOf<String?>(null) }

    if (addingFile) {
        TextPromptDialog(
            title = "New file",
            initial = "",
            confirmLabel = "Add",
            placeholder = "path/to/file.ext",
            onDismiss = { addingFile = false },
            onConfirm = { path -> vm.addFile(artifact.id, path); addingFile = false; onSelect(path) },
        )
    }
    renamingPath?.let { old ->
        TextPromptDialog(
            title = "Rename file",
            initial = old,
            confirmLabel = "Rename",
            onDismiss = { renamingPath = null },
            onConfirm = { new -> vm.renameFile(artifact.id, old, new); renamingPath = null; onSelect(new) },
        )
    }
    regenTarget?.let { path ->
        TextPromptDialog(
            title = "Regenerate ${path.substringAfterLast('/')}",
            initial = "",
            confirmLabel = "Regenerate",
            placeholder = "What should change? (leave blank to just improve it)",
            onDismiss = { regenTarget = null },
            onConfirm = { instruction -> vm.regenerateFile(artifact, path, instruction); regenTarget = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FILES", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelSmall, color = FgMuted)
            IconButton(onClick = { addingFile = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, "Add file", tint = Accent2, modifier = Modifier.size(16.dp))
            }
        }
        FileTree(
            artifact, current?.path, onSelect,
            onRename = { renamingPath = it },
            onDelete = { path -> vm.deleteFile(artifact.id, path); if (path == current?.path) onSelect(artifact.files.firstOrNull { it.path != path }?.path ?: "") },
            onRegenerate = { regenTarget = it },
            regenerating = regenerating,
        )
        HorizontalDivider(color = BorderCol)

        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files", color = FgMuted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = FgMuted, maxLines = 1)
            IconButton(onClick = { clipboard.setText(AnnotatedString(draft)); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = FgMuted, modifier = Modifier.size(15.dp))
            }
            AnimatedVisibility(dirty) {
                IconButton(onClick = { vm.updateFileContent(artifact.id, current.path, draft) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Save, "Save", tint = Success, modifier = Modifier.size(16.dp))
                }
            }
        }

        CodeEditor(
            code = draft,
            onChange = { draft = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** A highlighted, editable code area: a transparent BasicTextField over a highlighted backdrop. */
@Composable
private fun CodeEditor(code: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = code,
            onValueChange = onChange,
            textStyle = MonoStyle.copy(color = Color(0xFFE6E4F0)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            visualTransformation = { text ->
                androidx.compose.ui.text.input.TransformedText(highlightAnnotated(text.text), androidx.compose.ui.text.input.OffsetMapping.Identity)
            },
        )
    }
}

internal val tokenColors = mapOf(
    TokenKind.KEYWORD to Color(0xFF7C5CFF),
    TokenKind.STRING to Color(0xFF5FD9A4),
    TokenKind.COMMENT to Color(0xFF6B6980),
    TokenKind.NUMBER to Color(0xFFFFB454),
    TokenKind.TAG to Color(0xFF8AB4FF),
)

internal fun highlightAnnotated(code: String): AnnotatedString = buildAnnotatedString {
    append(code)
    highlightSpans(code).forEach { span ->
        tokenColors[span.kind]?.let { color ->
            addStyle(SpanStyle(color = color, fontWeight = if (span.kind == TokenKind.KEYWORD) FontWeight.SemiBold else FontWeight.Normal), span.range.first, span.range.last + 1)
        }
    }
}

@Composable
private fun CanvasView(vm: AppViewModel, artifact: Artifact, selectedPath: String?, onSelect: (String) -> Unit, onExit: () -> Unit) {
    val current = artifact.files.firstOrNull { it.path == selectedPath } ?: artifact.files.firstOrNull()
    var draft by remember(current?.path, current?.content) { mutableStateOf(current?.content.orEmpty()) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.path.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = FgMuted, maxLines = 1)
            IconButton(onClick = onExit) { Icon(Icons.Default.FullscreenExit, "Exit canvas") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            artifact.files.forEach { f ->
                FilterChip(
                    selected = f.path == current?.path,
                    onClick = { onSelect(f.path) },
                    label = { Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.22f), containerColor = BgElevated),
                )
            }
        }
        if (current != null) {
            CodeEditor(
                code = draft,
                onChange = { draft = it; vm.updateFileContent(artifact.id, current.path, it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Run / live preview
// ---------------------------------------------------------------------------------------------

@Composable
private fun RunView(artifact: Artifact) {
    val html = remember(artifact.files) { buildLivePreviewHtml(artifact.files) }
    if (html == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.PlayCircle, null, tint = FgMuted, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("Nothing to run here", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Run works for web projects with an .html file. This project doesn't have one — " +
                    "check the Code tab, or use Android APK / My Terminal for anything else.",
                style = MaterialTheme.typography.bodySmall,
                color = FgMuted,
            )
        }
        return
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { webView -> webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
    )
}

// ---------------------------------------------------------------------------------------------
// APK / Terminal (unchanged behaviour, same as before)
// ---------------------------------------------------------------------------------------------

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
                "through the terminal you connected. ChomuGiri copies the project over, then " +
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

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    placeholder: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = placeholder?.let { { Text(it, color = FgMuted) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent.copy(alpha = 0.6f),
                    unfocusedBorderColor = BorderCol,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel, color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = FgMuted) } },
    )
}

private fun shareArtifactZip(context: android.content.Context, artifact: Artifact) {
    try {
        val cacheDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(cacheDir, suggestedZipName(artifact))
        java.io.FileOutputStream(file).use { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                artifact.files.forEach { f ->
                    val safe = f.path.trimStart('/').replace("..", "_")
                    if (safe.isBlank()) return@forEach
                    zip.putNextEntry(java.util.zip.ZipEntry(safe))
                    zip.write(f.content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share project"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
