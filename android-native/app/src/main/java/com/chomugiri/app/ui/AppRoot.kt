package com.chomugiri.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chomugiri.app.core.conversationToMarkdown
import com.chomugiri.app.data.AppViewModel
import kotlinx.coroutines.launch

enum class Screen { CHAT, ARTIFACTS, TERMINAL, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var chatSearch by remember { mutableStateOf("") }

    val conversations by vm.conversations.collectAsState()
    val activeConvId by vm.activeConversationId.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val activeArtifactId by vm.activeArtifactId.collectAsState()

    val openArtifact = artifacts.firstOrNull { it.id == activeArtifactId }

    // The project panel is a full screen of its own — back should close it, not exit the app.
    BackHandler(enabled = openArtifact != null) { vm.closeArtifact() }
    BackHandler(enabled = openArtifact == null && screen != Screen.CHAT) { screen = Screen.CHAT }

    val pending by vm.pendingPermission.collectAsState()
    pending?.let { req -> AgentPermissionDialog(req) }

    if (openArtifact != null) {
        ArtifactScreen(vm, openArtifact) { vm.closeArtifact() }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // An unconstrained ModalDrawerSheet can end up the same width as the scrim behind
            // it on a narrow screen, so a tap on a drawer item can land on the scrim's dismiss
            // handler instead — the item never fires and the drawer just closes. A fixed width
            // keeps the two apart.
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = DrawerBg,
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Logomark(size = 34.dp)
                        Spacer(Modifier.width(11.dp))
                        Text("ChomuGiri", style = MaterialTheme.typography.titleLarge)
                    }

                    DrawerItem(Icons.Default.Add, "New chat", false) {
                        vm.newConversation(); screen = Screen.CHAT
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem(Icons.Default.Dashboard, "Projects", screen == Screen.ARTIFACTS) {
                        screen = Screen.ARTIFACTS
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem(Icons.Default.Terminal, "My Terminal", screen == Screen.TERMINAL) {
                        screen = Screen.TERMINAL
                        scope.launch { drawerState.close() }
                    }

                    Text(
                        "Chats",
                        Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = FgMuted,
                    )

                    if (conversations.size > 3) {
                        OutlinedTextField(
                            value = chatSearch,
                            onValueChange = { chatSearch = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(46.dp),
                            placeholder = { Text("Search chats", style = MaterialTheme.typography.bodySmall, color = FgMuted) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = FgMuted, modifier = Modifier.size(16.dp)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent.copy(alpha = 0.6f),
                                unfocusedBorderColor = BorderCol,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    val filteredChats = conversations
                        .filter { chatSearch.isBlank() || it.title.contains(chatSearch, ignoreCase = true) }
                        .sortedByDescending { it.updatedAt }

                    LazyColumn(Modifier.weight(1f)) {
                        items(filteredChats, key = { it.id }) { c ->
                            DrawerItem(Icons.Default.ChatBubbleOutline, c.title, c.id == activeConvId) {
                                vm.selectConversation(c.id); screen = Screen.CHAT
                                scope.launch { drawerState.close() }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderCol)
                    DrawerItem(Icons.Default.Settings, "Settings", screen == Screen.SETTINGS) {
                        screen = Screen.SETTINGS
                        scope.launch { drawerState.close() }
                    }
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        },
    ) {
        val bg by androidx.compose.animation.animateColorAsState(BgDark, androidx.compose.animation.core.tween(200), label = "bg")
        Column(Modifier.fillMaxSize().background(bg).statusBarsPadding()) {
            if (screen != Screen.SETTINGS) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, "Menu")
                    }
                    Text(
                        when (screen) {
                            Screen.CHAT -> conversations.firstOrNull { it.id == activeConvId }?.title ?: "ChomuGiri"
                            Screen.ARTIFACTS -> "Projects"
                            Screen.TERMINAL -> "My Terminal"
                            Screen.SETTINGS -> "Settings"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    if (screen == Screen.CHAT) {
                        val activeConvo = conversations.firstOrNull { it.id == activeConvId }
                        if (activeConvo != null && activeConvo.messages.isNotEmpty()) {
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, conversationToMarkdown(activeConvo))
                                }
                                context.startActivity(Intent.createChooser(intent, "Share chat"))
                            }) {
                                Icon(Icons.Default.IosShare, "Export chat", tint = FgMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Crossfade(targetState = screen, label = "screen", animationSpec = tween(180)) { s ->
                when (s) {
                    Screen.CHAT -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenArtifact = { vm.openArtifact(it) },
                    )
                    Screen.ARTIFACTS -> ArtifactsListScreen(vm)
                    Screen.TERMINAL -> TerminalScreen(vm) { screen = Screen.SETTINGS }
                    Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT }
                }
            }
        }
    }
}

@Composable
private fun AgentPermissionDialog(req: com.chomugiri.app.data.AgentPermissionRequest) {
    AlertDialog(
        onDismissRequest = { req.respond(false) },
        containerColor = BgElevated,
        icon = {
            Icon(
                if (req.kind == "compile") Icons.Default.Android else Icons.Default.Description,
                null,
                tint = Accent,
            )
        },
        title = { Text(if (req.kind == "compile") "Compile on your machine?" else "Read a file?") },
        text = { Text(req.description, color = FgMuted) },
        confirmButton = {
            TextButton(onClick = { req.respond(true) }) { Text("Allow", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = { req.respond(false) }) { Text("Deny", color = FgMuted) }
        },
    )
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
        // Lets the user see at a glance that a different chat is still generating in the
        // background — several chats can each be mid-pipeline at once now.
        badge = if (busy) {
            { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Accent) }
        } else null,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Accent.copy(alpha = 0.18f),
            unselectedContainerColor = Color.Transparent,
        ),
    )
}

/** A real, derived signal — not an AI opinion — from what the swarm actually reported for this project. */
@Composable
private fun healthBadge(a: com.chomugiri.app.core.Artifact): Pair<String, Color>? = when {
    a.resolvedBy == null -> null
    a.lastAuditIssues.isEmpty() -> "Clean" to Success
    else -> "${a.lastAuditIssues.size} fixed" to Accent2
}

private fun formatBuildStats(a: com.chomugiri.app.core.Artifact): String? {
    val ms = a.buildMs ?: return null
    val seconds = ms / 1000
    val time = if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
    val rounds = a.auditRounds?.let { " · $it audit round${if (it == 1) "" else "s"}" } ?: ""
    return "Built in $time$rounds"
}

@Composable
internal fun ArtifactsListScreen(vm: AppViewModel) {
    val artifacts by vm.artifacts.collectAsState()
    var search by remember { mutableStateOf("") }

    val list = artifacts
        .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
        .sortedWith(compareByDescending<com.chomugiri.app.core.Artifact> { it.pinned }.thenByDescending { it.createdAt })

    if (artifacts.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Dashboard, null, tint = FgMuted, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text("No projects yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask ChomuGiri to build something in Chat. Whatever it generates lands here, " +
                    "with Code, Canvas, .zip export and a real APK build attached to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = FgMuted,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (artifacts.size > 3) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search projects", color = FgMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = FgMuted, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent.copy(alpha = 0.6f),
                    unfocusedBorderColor = BorderCol,
                ),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { a ->
                Surface(
                    color = BgElevated,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.openArtifact(a.id) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                healthBadge(a)?.let { (label, color) ->
                                    Spacer(Modifier.width(8.dp))
                                    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                        Text(label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = color)
                                    }
                                }
                            }
                            Text(
                                "${a.files.size} file(s)" + (formatBuildStats(a)?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = FgMuted,
                            )
                        }
                        Icon(
                            if (a.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            "Pin",
                            tint = if (a.pinned) Accent2 else FgMuted,
                            modifier = Modifier.size(18.dp).clickable { vm.togglePin(a.id) },
                        )
                    }
                }
            }
        }
    }
}
