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

    /**
     * Whether this page may serve as the app's home screen.
     *
     * The default keeps the original contract: a plugin that says nothing is eligible, which is what
     * every page was before this property existed. A page that is only reachable from a menu - memory,
     * tools, a terminal - sets it to false, so it can never become the launch surface just because
     * the real home plugin is disabled or failed to load.
     */
    val isHomeCandidate: Boolean get() = true

    @Composable
    fun Page(modifier: Modifier, onBack: () -> Unit)

    /**
     * Rendered as the app root: there is nothing to go back to, so the top bar offers a way into
     * the host instead. Defaults to [Page] with that affordance mapped to the host entry.
     */
    @Composable
    fun Home(modifier: Modifier, onOpenHost: () -> Unit) = Page(modifier, onOpenHost)
}
