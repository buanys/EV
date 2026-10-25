package ev.buan

import android.content.Context
import ev.buan.hostcore.storage.PluginStorageFiles
import ev.buan.hostcore.storage.PluginStorageLayout
import ev.buan.hostcore.storage.StorageQuotaExceededException
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageQuotaExceededException

/**
 * One plugin's [PluginStorage] binding.
 *
 * Thin adapter over `:host-core` (path rules, quota, atomic write); it only maps the host-core
 * exception type to the published contract exception. The host creates one instance per plugin and
 * provides it on that plugin's child Context (the T06 scope), so the plugin id is fixed here and a
 * plugin cannot address another plugin's area.
 *
 * When [permissions] is present - the instance handed to a plugin - every data call starts with
 * `require(PLUGIN_STORAGE)`. That makes the capability the plugin declared in its metadata a
 * service-level gate, not just an activation-time one: a grant revoked while it runs fails the next
 * call with `PERMISSION_DENIED` instead of leaving a working file area behind. [usageBytes] and
 * [clear] deliberately stay outside the gate: they are the host's management path (the management
 * page's accounting and its「清理」button), which has to work while the plugin is disabled, failed
 * or unprivileged.
 */
class PluginStorageImpl(
    private val pluginId: String,
    private val files: PluginStorageFiles,
    /** Grants of the plugin that will hold this instance; null on the host's own management path. */
    private val permissions: PermissionService? = null,
) : PluginStorage {

    /** Binds the conventional layout under the app's files/ and cache/ directories. */
    constructor(context: Context, pluginId: String) : this(pluginId, filesFor(context, pluginId))

    /** Plugin-facing binding: the conventional layout plus the plugin's own grant state. */
    constructor(context: Context, pluginId: String, permissions: PermissionService) :
        this(pluginId, filesFor(context, pluginId), permissions)

    override fun read(path: String): ByteArray? {
        requireStorage()
        return files.read(path)
    }

    override fun write(path: String, bytes: ByteArray) {
        requireStorage()
        translateQuota { files.write(path, bytes) }
    }

    override fun atomicWrite(path: String, bytes: ByteArray) {
        requireStorage()
        translateQuota { files.atomicWrite(path, bytes) }
    }

    override fun delete(path: String): Boolean {
        requireStorage()
        return files.delete(path)
    }

    override fun list(path: String): List<String> {
        requireStorage()
        return files.list(path)
    }

    /** Bytes used by the persistent area; shown on the management page. Not permission-gated. */
    fun usageBytes(): Long = files.usageBytes()

    /** Deletes both areas (management「清理」or plugin uninstall). Not permission-gated. */
    fun clear() {
        files.clear()
    }

    /** Fails closed before touching the file system, so a revoked plugin cannot even read. */
    private fun requireStorage() {
        permissions?.require(HostPermission.PLUGIN_STORAGE)
    }

    private inline fun translateQuota(block: () -> Unit) {
        try {
            block()
        } catch (quota: StorageQuotaExceededException) {
            throw PluginStorageQuotaExceededException(
                quota.message ?: "插件 $pluginId 存储超过配额",
                quota,
            )
        }
    }
}

/**
 * Installs the per-plugin storage provider when - and only when - the plugin declared
 * [HostPermission.PLUGIN_STORAGE].
 *
 * Declaration and grant fail at different layers by design:
 *  - a plugin that never declared the capability gets no binding at all, so `ctx[PluginStorage.Key]`
 *    is null and it cannot even hold the API;
 *  - a plugin that declared it but was not granted it still gets the binding, but every data call
 *    throws [SecurityException] until the user grants it in the management page - no reload needed;
 *  - [PluginStorageImpl.usageBytes] and [PluginStorageImpl.clear] remain the host's own path.
 *
 * [storage] is a factory so an undeclared plugin never even constructs the implementation (that
 * needs the Android context to resolve its files/ and cache/ roots). Returns whether a binding was
 * installed.
 */
internal suspend fun providePluginStorageIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    storage: () -> PluginStorage,
): Boolean {
    if (HostPermission.PLUGIN_STORAGE !in requested) return false
    pluginContext.provide(PluginStorage.Key, storage()).awaitReady()
    return true
}

private fun filesFor(context: Context, pluginId: String): PluginStorageFiles = PluginStorageFiles(
    PluginStorageLayout.dataRoot(context.filesDir, pluginId),
    PluginStorageLayout.cacheRoot(context.cacheDir, pluginId),
)
