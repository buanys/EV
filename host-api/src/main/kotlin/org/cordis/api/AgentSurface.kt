package org.cordis.api

/**
 * Version of the host <-> plugin contract carried by this module.
 *
 * A host must reject a plugin whose declared version it does not support. The constant is
 * intentionally part of the module rather than a manifest field so a stale plugin fails at
 * class-load time instead of producing silently wrong behaviour.
 */
const val PLUGIN_API_VERSION: Int = 1

/**
 * Business surface a plugin exposes to the host.
 *
 * The host renders this data itself, so a plugin can appear in the UI without shipping any
 * Compose code of its own. Plugin-supplied composables arrive with the next API revision.
 */
interface AgentSurface {
    val title: String
    val body: String

    /** Contract revision the plugin was compiled against. */
    val apiVersion: Int get() = PLUGIN_API_VERSION
}
