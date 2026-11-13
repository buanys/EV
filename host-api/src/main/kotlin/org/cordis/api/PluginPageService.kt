package org.cordis.api

import org.cordis.ServiceKey

/**
 * Resolves one plugin's full-screen page so another surface can render it in place.
 *
 * [AgentPage] has always been reachable through `PluginPageActivity`, but that path puts a plugin
 * page on a *system* back stack with a system transition. A surface with its own page stack (ev-ui)
 * would then produce two different transitions for the same gesture, and back would sometimes
 * animate as a page and sometimes as an Activity.
 *
 * Handing the page back lets the caller compose it inside its own stack: same transition, same back
 * handling, one Activity. The host still owns activation, permissions and the theme - this service
 * only answers "which page belongs to this id".
 *
 * Returns null when the plugin is unknown, disabled, failed to activate, or simply has no page.
 * Callers must treat null as "nothing to show" and never render an empty page in its place.
 */
interface PluginPageService {

    fun pageOf(pluginId: String): AgentPage?

    companion object {
        val Key = ServiceKey<PluginPageService>("host/plugin-page")
    }
}
