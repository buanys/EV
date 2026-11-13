package org.cordis.api

import org.cordis.ServiceKey

/**
 * Everything the host knows about one installed container, in a form a plugin page can render.
 *
 * Separate from [PluginManifest] on purpose: the manifest is what the plugin says about itself, this
 * is what the host observed - whether it loaded, what the user granted, how much space it uses.
 */
data class ManagedPlugin(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val signed: Boolean,
    /** Why it did not load, if it did not. Null means it is running. */
    val error: String? = null,
    /** Permissions the container declares. */
    val requestedPermissions: List<String> = emptyList(),
    /** Of those, the ones the user currently grants. */
    val grantedPermissions: List<String> = emptyList(),
    /** Bytes used by this plugin's own storage area. */
    val storageBytes: Long = 0,
)

/**
 * Management operations a settings page may perform.
 *
 * The host stays the only writer: a plugin can ask for a change, it cannot perform one, and every
 * call lands in the same code path the host's own manager page uses.
 */
interface PluginManagerService {
    fun plugins(): List<ManagedPlugin>

    /** Enables or disables a container; disabling unloads it. */
    fun setEnabled(id: String, enabled: Boolean)

    fun grantPermission(id: String, permission: String)

    fun revokePermission(id: String, permission: String)

    /** Deletes this plugin's private storage. Irreversible. */
    fun clearStorage(id: String)

    companion object {
        val Key = ServiceKey<PluginManagerService>("host/plugin-manager")
    }
}
