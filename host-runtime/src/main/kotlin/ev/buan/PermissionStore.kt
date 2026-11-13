package ev.buan

import android.content.Context
import android.content.SharedPreferences
import org.cordis.api.HostPermission
import ev.buan.hostcore.validation.PluginRejectionCode

/**
 * Per-plugin permission grants, stored in `ev.permissions` (one string set per plugin id).
 *
 * This is the single source of truth: activation reads it, the management page grants and revokes
 * through it, and [PermissionServiceImpl] re-reads it on every call so a revoke applies to the next
 * host service call rather than only to the next activation.
 *
 * Permission names are stored, not ordinals: adding a constant never reinterprets existing grants,
 * and a name this build does not know is dropped from the set instead of becoming a capability.
 */
class PermissionStore(private val preferences: SharedPreferences) {

    /** Production wiring. Tests construct the store from an in-memory [SharedPreferences]. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    /** id -> granted permissions, for the validator's install-time policy. */
    fun grants(): Map<String, Set<HostPermission>> =
        preferences.all.mapNotNull { (id, value) ->
            (value as? Set<*>)?.let { id to it.toPermissions() }
        }.toMap()

    fun granted(id: String): Set<HostPermission> =
        preferences.getStringSet(id, emptySet()).orEmpty().toPermissions()

    fun grant(id: String, permission: HostPermission) {
        setGranted(id, granted(id) + permission)
    }

    fun revoke(id: String, permission: HostPermission) {
        setGranted(id, granted(id) - permission)
    }

    fun setGranted(id: String, permissions: Set<HostPermission>) {
        preferences.edit()
            .putStringSet(id, permissions.mapTo(LinkedHashSet()) { it.name })
            .apply()
    }

    private fun Set<*>.toPermissions(): Set<HostPermission> =
        mapNotNullTo(LinkedHashSet()) { value -> (value as? String)?.let(HostPermission::fromWireName) }

    private companion object {
        const val PREFERENCES_NAME = "ev.permissions"
    }
}

/**
 * The activation-time permission decision.
 *
 * Install and activation deliberately differ: installing only places bytes in private storage (so
 * the management page can show the plugin and let the user grant it), while activation is what
 * actually lends capability. This gate therefore runs at activation; the install pass treats
 * `PERMISSION_DENIED` from [ev.buan.hostcore.validation.PluginValidator] as "installed, not active".
 */
internal object PermissionGate {

    fun missing(requested: Set<HostPermission>, granted: Set<HostPermission>): Set<HostPermission> =
        HostPermission.missing(requested, granted)

    /** Stable failure text: the code leads so the UI, logs and tests agree on one identity. */
    fun denialMessage(pluginId: String, missing: Set<HostPermission>): String {
        val names = HostPermission.entries.filter { it in missing }.joinToString(", ") { it.name }
        return "${PluginRejectionCode.PERMISSION_DENIED.name}: 插件 $pluginId 缺少权限 $names" +
            "（在插件管理页授予后自动启用）"
    }
}
