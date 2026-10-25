package org.cordis.api

import ev.buan.ui.EvAppearance
import org.cordis.ServiceKey

/**
 * Host-owned appearance state.
 *
 * The theme must live in the host: [ev.buan.ui.EvTheme] is applied above the plugin's composition,
 * so every plugin inherits colors and typography without doing anything. A settings UI therefore
 * cannot own the state - it asks the host to change it through this service.
 */
interface AppearanceService {
    /** Current appearance. Read it during composition to stay in sync. */
    val current: EvAppearance

    /** Applies [transform] and persists the result. Recomposition follows automatically. */
    fun update(transform: (EvAppearance) -> EvAppearance)

    companion object {
        val Key = ServiceKey<AppearanceService>("host/appearance")
    }
}
