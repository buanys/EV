package ev.buan.plugins.ev.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import ev.buan.plugins.ev.ui.ui.EvDrawerContent
import ev.buan.ui.EvPage

/**
 * The slide-over drawer's content and its wiring.
 *
 * Extracted from the surface so the plugin class does not carry sixteen lambdas of navigation. The
 * drawer mutates [EvChatUiState] and asks [ChatSessionController] for conversation changes; it never
 * touches the turn machinery directly.
 */
@Composable
internal fun EvChatDrawer(
    ui: EvChatUiState,
    sessions: SnapshotStateList<ChatSession>,
    chat: ChatSessionController,
    activeSessionId: String,
    statusLine: String,
    newSession: () -> ChatSession,
    openPlugins: () -> Unit,
    openSettings: () -> Unit,
    openSettingsSection: (SettingsChild) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    EvDrawerContent(
    groups = historyGroups(sessions, activeSessionId, ui.searchQuery),
    statusLine = statusLine,
    searching = ui.searching,
    query = ui.searchQuery,
    onQueryChange = { ui.searchQuery = it },
    onSelectSession = { id ->
        chat.select(id)
        ui.searching = false
        ui.searchQuery = ""
        keyboard?.hide()
        ui.drawerOpen = false
    },
    onDeleteSession = { id ->
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) {
            val wasActive = sessions[index].id == chat.activeSessionId
            sessions.removeAt(index)
            // 全删光之后必须补一个空会话：界面上总得有一个可写的地方，
            // 否则下一次发送没有目标会话。
            if (sessions.isEmpty()) sessions += newSession()
            if (wasActive) chat.select(sessions.first().id)
            chat.persist()
        }
    },
    onPlugins = {
        openPlugins()
        ui.drawerOpen = false
    },
    onModel = {
        // The drawer is the second way into the same in-plugin provider page as
        // Settings -> 模型服务; host plugin management is only reached from there.
        keyboard?.hide()
        ui.drawerOpen = false
        openSettingsSection(SettingsChild.Models)
    },
    onTools = {
        // The read-only panel for whatever agent-tools publishes; the settings page's
        // own "工具" row keeps pointing at the plugin manager for enabling it.
        keyboard?.hide()
        ui.drawerOpen = false
        ui.pages.push(EvPage.Tools)
    },
    onBrowser = {
        keyboard?.hide()
        ui.drawerOpen = false
        ui.pages.push(EvPage.Browser)
    },
    onMemory = {
        // The active conversation is what the panel reads and can clear; clearing it
        // leaves the chat bubbles above untouched.
        keyboard?.hide()
        ui.drawerOpen = false
        ui.pages.push(EvPage.Memory)
    },
    onSettings = {
        // Ev's own settings page, inside this plugin: the host page stays reachable
        // from the drawer's "宿主设置" row on the settings screen itself.
        keyboard?.hide()
        ui.drawerOpen = false
        openSettings()
    },
    onSearch = {
        ui.searching = !ui.searching
        if (!ui.searching) ui.searchQuery = ""
    },
    moreExpanded = ui.drawerMoreExpanded,
    onToggleMore = { ui.drawerMoreExpanded = !ui.drawerMoreExpanded },
                )
}
