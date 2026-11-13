package org.cordis.api

import ev.buan.ui.EvThemeSpec
import org.cordis.ServiceKey

/**
 * A theme plugin's contribution: the app's visual definition, published as data.
 *
 * This is the whole point of the split. Layout lives in the UI plugin; what the app *looks* like
 * lives here. Replacing one theme plugin replaces every colour, radius, type ramp and glass
 * parameter at once, without touching a single layout file - and a user who dislikes the shipped
 * look swaps exactly this plugin instead of the entire interface.
 *
 * @see ThemeCatalogService for the host-owned view a UI reads.
 */
interface ThemeService {
    /**
     * Every theme this plugin offers, in the order it wants them shown. An empty list is legal and
     * reads as "this plugin currently defines nothing", which the host falls back from.
     */
    fun themes(): List<EvThemeSpec>

    companion object {
        val Key = ServiceKey<ThemeService>("host/theme-provider")
    }
}

/**
 * The set of themes the app can currently apply, whatever plugin happens to publish them.
 *
 * Host-owned on purpose: the UI must not care whether a theme came from a plugin or from the
 * compiled-in fallback, and a plugin being disabled must not leave the app unable to render. The
 * host merges the active [ThemeService] over the built-in default and answers both questions from
 * that merged view.
 */
interface ThemeCatalogService {
    /** Selectable themes, plugin themes first, always ending with a usable fallback. */
    fun available(): List<EvThemeSpec>

    /** The theme for [id]; an unknown id resolves to the fallback rather than failing. */
    fun resolve(id: String): EvThemeSpec

    companion object {
        val Key = ServiceKey<ThemeCatalogService>("host/theme-catalog")
    }
}
