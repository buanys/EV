package ev.buan

import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import ev.buan.hostcore.validation.PluginRejectionCode

/**
 * One plugin's view of its own grants.
 *
 * The host provides exactly one of these per plugin, on that plugin's own child Context, so the
 * plugin id is captured here rather than taken from the caller - a plugin cannot ask about, or
 * require a permission for, another plugin. Every call re-reads [PermissionStore]: revocation takes
 * effect immediately for host-mediated services, not just for the next activation.
 */
internal class PermissionServiceImpl(
    private val pluginId: String,
    private val store: PermissionStore,
) : PermissionService {

    override fun isGranted(permission: HostPermission): Boolean = permission in store.granted(pluginId)

    override fun require(permission: HostPermission) {
        if (isGranted(permission)) return
        throw SecurityException(
            "${PluginRejectionCode.PERMISSION_DENIED.name}: 插件 $pluginId 未获授权 ${permission.name}",
        )
    }
}
