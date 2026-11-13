package ev.buan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cordis.api.HostPermission
import org.cordis.api.ManagedPlugin
import org.cordis.api.PluginManagerService

/**
 * The host's plugin operations, offered to any UI that wants them.
 *
 * Every call delegates to the same routines the host's own manager page uses, so the two surfaces
 * cannot drift: enabling from a plugin page is exactly enabling from [PluginsActivity]. The calls are
 * suspend underneath and fire-and-forget here, because a settings row cannot await; the resulting
 * state flows back through [HostComposition.state] like any other change.
 */
internal class HostPluginManager(
    private val runtime: HostComposition,
    private val scope: CoroutineScope,
) : PluginManagerService {

    override fun plugins(): List<ManagedPlugin> =
        runtime.state.value?.cards.orEmpty().map { card ->
            ManagedPlugin(
                id = card.id,
                name = card.title.ifBlank { card.id },
                version = card.version,
                enabled = card.enabled,
                signed = card.signed,
                error = card.error,
                requestedPermissions = card.requestedPermissions.map { it.name },
                grantedPermissions = card.grantedPermissions.map { it.name },
                storageBytes = card.storageBytes,
            )
        }

    override fun setEnabled(id: String, enabled: Boolean) {
        scope.launch { runtime.setEnabled(id, enabled) }
    }

    override fun grantPermission(id: String, permission: String) {
        changePermission(id, permission, granted = true)
    }

    override fun revokePermission(id: String, permission: String) {
        changePermission(id, permission, granted = false)
    }

    override fun clearStorage(id: String) {
        scope.launch { runtime.clearStorage(id) }
    }

    private fun changePermission(id: String, permission: String, granted: Boolean) {
        val parsed = HostPermission.entries.firstOrNull { it.name == permission } ?: return
        scope.launch { runtime.setPermission(id, parsed, granted) }
    }
}
