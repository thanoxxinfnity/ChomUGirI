package com.chomugiri.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chomugiri.app.data.AppViewModel
import kotlinx.coroutines.launch

enum class Screen { CHAT, ARTIFACTS, TERMINAL, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val conversations by vm.conversations.collectAsState()
    val activeConvId by vm.activeConversationId.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val activeArtifactId by vm.activeArtifactId.collectAsState()

    val openArtifact = artifacts.firstOrNull { it.id == activeArtifactId }

    // The project panel is a full screen of its own — back should close it, not exit the app.
    BackHandler(enabled = openArtifact != null) { vm.closeArtifact() }
    BackHandler(enabled = openArtifact == null && screen != Screen.CHAT) { screen = Screen.CHAT }

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
                drawerContainerColor = Color(0xFF08080C),
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Logomark(size = 34.dp)
                        Spacer(Modifier.width(11.dp))
                        Text("ChomuGirI", style = MaterialTheme.typography.titleLarge)
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

                    LazyColumn(Modifier.weight(1f)) {
                        items(conversations.sortedByDescending { it.updatedAt }, key = { it.id }) { c ->
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
        Column(Modifier.fillMaxSize().background(BgDark).statusBarsPadding()) {
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
                            Screen.CHAT -> conversations.firstOrNull { it.id == activeConvId }?.title ?: "ChomuGirI"
                            Screen.ARTIFACTS -> "Projects"
                            Screen.TERMINAL -> "My Terminal"
                            Screen.SETTINGS -> "Settings"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }

            when (screen) {
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

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Accent.copy(alpha = 0.18f),
            unselectedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun ArtifactsListScreen(vm: AppViewModel) {
    val artifacts by vm.artifacts.collectAsState()
    val list = artifacts.sortedByDescending { it.createdAt }

    if (list.isEmpty()) {
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
                "Ask ChomuGirI to build something in Chat. Whatever it generates lands here, " +
                    "with Code, Canvas, .zip export and a real APK build attached to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = FgMuted,
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { it.id }) { a ->
            Surface(
                color = BgElevated,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.openArtifact(a.id) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "${a.files.size} file(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = FgMuted,
                    )
                }
            }
        }
    }
}
