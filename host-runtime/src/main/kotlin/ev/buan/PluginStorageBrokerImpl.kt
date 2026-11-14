package ev.buan

import android.content.Context
import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageBroker

/**
 * Root-level access to each plugin's own storage area.
 *
 * Instances are built lazily and cached: `PluginStorageImpl` carries no state
 * beyond its paths and the plugin's grant handle, so one per id is enough and
 * keeps `usageBytes` accounting stable.
 */
internal class PluginStorageBrokerImpl(
    private val context: Context,
    private val declared: (String) -> Boolean,
    private val permissions: (String) -> org.cordis.api.PermissionService?,
) : PluginStorageBroker {

    private val lock = Any()
    private val cache = mutableMapOf<String, PluginStorage>()

    override fun storageFor(owner: String): PluginStorage? {
        if (!declared(owner)) return null
        return synchronized(lock) {
            cache.getOrPut(owner) {
                // 不带 PermissionService：broker 这一路是宿主代表插件取路径，
                // 授权门留在 PluginStorageImpl 的插件实例上（见类注释）。
                PluginStorageImpl(context, owner)
            }
        }
    }
}
