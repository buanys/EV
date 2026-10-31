package ev.buan.ui

import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList

/** Id of the built-in theme. Also the value every appearance state without a theme falls back to. */
const val DEFAULT_THEME_ID = "ev-default"

/**
 * Themes the user can pick. Built-in themes are compiled in; external themes are added through
 * [register] (reserved for theme plugins, not wired to any loader yet).
 *
 * Resolution never fails: an unknown or removed id resolves to [EvThemeSpec.Default], so a theme
 * that disappears never leaves the app without a look.
 */
object EvThemeRegistry {
    /**
     * The one look compiled into the app.
     *
     * Theme *definitions* belong to a theme plugin; this is the fallback that keeps the app
     * drawable when none is installed (the shipped state) and the last resort if one misbehaves.
     */
    private val builtIn: List<EvThemeSpec> = listOf(EvThemeSpec.Default)

    private val external = CopyOnWriteArrayList<EvThemeSpec>()

    /** Every selectable theme: built-in first, then external in registration order. */
    fun all(): List<EvThemeSpec> = builtIn + external

    /** The theme for [id], or [EvThemeSpec.Default] when the id is unknown. */
    fun resolve(id: String): EvThemeSpec = all().firstOrNull { it.id == id } ?: EvThemeSpec.Default

    /**
     * Adds or replaces an external theme. Built-in ids are reserved: attempting to shadow one is
     * rejected and returns false, so a third-party theme can never silently change the built-ins.
     */
    fun register(spec: EvThemeSpec): Boolean {
        if (builtIn.any { it.id == spec.id }) return false
        external.removeAll { it.id == spec.id }
        external += spec
        return true
    }

    /** Removes an external theme. Built-in themes cannot be removed. */
    fun unregister(id: String) {
        external.removeAll { it.id == id }
    }
}
