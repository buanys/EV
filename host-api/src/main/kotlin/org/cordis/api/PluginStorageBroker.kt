package org.cordis.api

import org.cordis.ServiceKey

/**
 * Hands a plugin its own storage area.
 *
 * Why this exists at all: `PluginStorage` is one instance per plugin, and the
 * host used to provide it on the plugin's isolated child context. The kernel's
 * dependency resolver does not hand a child a binding its parent isolated -
 * the plugin got `cannot get property "host/storage" without inject` from
 * `ctx.require`, and an `inject` entry left the fiber `PENDING` instead. Neither
 * shape could ever start.
 *
 * So the binding moved up one level: the root context publishes this broker,
 * which every plugin can reach, and a plugin asks for its own area by the id it
 * already declares in its manifest.
 *
 * This is a convention, not a sandbox - plugins share the host process by
 * design (ADR-0003), so a hostile plugin could ask for another id. What the
 * broker does enforce is the declaration gate: an id that did not declare
 * `PLUGIN_STORAGE` gets nothing, and the per-plugin quota still applies.
 */
interface PluginStorageBroker {
    /** The area of [owner], or null when that plugin never declared the capability. */
    fun storageFor(owner: String): PluginStorage?

    companion object {
        val Key = ServiceKey<PluginStorageBroker>("host/plugin-storage-broker")
    }
}
