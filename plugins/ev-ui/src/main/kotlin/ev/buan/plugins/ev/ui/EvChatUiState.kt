package ev.buan.plugins.ev.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The transient UI state of the chat surface: what is open, what is being typed, which page is on
 * top.
 *
 * It lives in its own object so the surface stops being the only place that knows every flag. The
 * drawer and the chat column take this one object instead of a dozen loose parameters, and the
 * surface keeps the turn logic that actually belongs to it.
 *
 * None of this is persisted: a draft, an open drawer and a half-typed search are things a restart
 * is allowed to forget. Conversation state lives in [ChatSessionController] instead.
 */
internal class EvChatUiState {
    /** The composer's text. */
    var draft by mutableStateOf("")

    var drawerOpen by mutableStateOf(false)

    /**
     * Kept above the drawer so the group stays open the next time it slides in: re-collapsing on
     * every open would punish anyone who actually uses those rows.
     */
    var drawerMoreExpanded by mutableStateOf(false)

    var searching by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    /** The composer's `+` panel and the top bar's model card: both close on any outside tap. */
    /** The right-hand quick menu (终端 / 浏览器 / …). */
    var moreMenuOpen by mutableStateOf(false)

    var plusPanelOpen by mutableStateOf(false)
    var modelSheetOpen by mutableStateOf(false)

    /** Full-screen surfaces on top of the conversation; back is one pop. */
    val pages = EvNavigator()
}
