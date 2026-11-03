package ev.buan.plugins.ev.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A full-screen surface that temporarily replaces the conversation.
 *
 * Before this existed, each surface owned a boolean (`showTools`, `showMemory`, ...), the render
 * path was an `if (...) { ...; return }` chain, and the system back button carried a second copy of
 * that same chain. Adding a page meant editing both, and forgetting one left a screen the user
 * could not leave. A page is now a value, and both paths read the same stack.
 */
internal sealed interface EvPage {
    /** Settings root. Second-level rows push a [SettingsSection] on top of this one. */
    data object Settings : EvPage

    /** One second-level settings page; back returns to [Settings], not to the conversation. */
    data class SettingsSection(val section: SettingsChild) : EvPage

    data object Tools : EvPage
    data object Browser : EvPage

    /**
     * Another plugin's own screen, composed inside this stack rather than started as an Activity.
     *
     * The page owns its content and its top bar; ev-ui owns the transition and the back gesture, so
     * opening a plugin page looks exactly like opening a built-in one.
     */
    data class Plugin(val id: String) : EvPage

    /** A shell that runs commands directly, without a conversation. */
    data object Terminal : EvPage
    data object Memory : EvPage
    data object Characters : EvPage
    data object Skills : EvPage

    /** The capability catalog: what every installed plugin declared about itself. */
    data object Plugins : EvPage

    /** The Debian environment: status, rootfs URL, install. */
    data object Linux : EvPage
}

/** The second-level settings rows. Values are stable ids; labels live in the settings screen. */
internal enum class SettingsChild {
    Themes,
    Models,
    Tools,
    Memory,
    Characters,
    Skills,
}

/**
 * Back stack for [EvPage] surfaces, observed by composition.
 *
 * Only page-level surfaces live here: transient overlays (drawer, `+` panel, model card) stay as
 * plain booleans because they are dismissed by tapping outside rather than by back, and stacking
 * them would make back feel like it was undoing taps.
 */
internal class EvNavigator {
    private val entries = mutableStateListOf<EvPage>()

    /** The page on top, or null when the conversation is showing. */
    val current: EvPage? get() = entries.lastOrNull()

    val isOpen: Boolean get() = entries.isNotEmpty()

    /**
     * True when the last change was a pop, so the transition can run backwards. AnimatedContent
     * cannot tell "back to the conversation" from "deeper into settings" by the states alone.
     */
    var lastWasPop: Boolean by mutableStateOf(false)
        private set

    fun push(page: EvPage) {
        entries.add(page)
        lastWasPop = false
    }

    /** Pops one page. Returns false when the conversation is already showing. */
    fun pop(): Boolean {
        if (entries.isEmpty()) return false
        entries.removeAt(entries.lastIndex)
        lastWasPop = true
        return true
    }
}

/** The memory plugin's id, used by both the drawer and the settings row that open its page. */
internal const val MEMORY_PLUGIN_ID = "ev.buan.plugins.agent.memory"

/** The tools plugin's id; it owns the registry and therefore the tools panel. */
internal const val TOOLS_PLUGIN_ID = "ev.buan.plugins.agent.tools"
