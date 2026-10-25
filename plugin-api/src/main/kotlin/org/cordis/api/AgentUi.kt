package org.cordis.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plugin-supplied UI.
 *
 * The host owns the composition, so a plugin only fills in content; it never creates an
 * Activity, a window or its own Compose runtime. The `@Composable` code still runs from the
 * plugin's own dex, calling the host's Compose classes through [HostPackages].
 */
interface AgentUi {
    @Composable
    fun Content(modifier: Modifier)
}
