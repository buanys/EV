package org.cordis.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A plugin screen that owns the whole page - its own top bar, its own scrolling, its own state.
 *
 * Separate from [AgentUi] on purpose: inline content is composed inside the host list and may be
 * composed more than once, which is fine for a settings form and wrong for a chat page whose
 * input text and scroll position would then drift apart between two compositions.
 */
interface AgentPage {
    val pageTitle: String

    @Composable
    fun Page(modifier: Modifier, onBack: () -> Unit)

    /**
     * Rendered as the app root: there is nothing to go back to, so the top bar offers a way into
     * the host instead. Defaults to [Page] with that affordance mapped to the host entry.
     */
    @Composable
    fun Home(modifier: Modifier, onOpenHost: () -> Unit) = Page(modifier, onOpenHost)
}
