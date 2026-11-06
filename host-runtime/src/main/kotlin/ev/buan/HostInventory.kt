package ev.buan

import android.content.SharedPreferences
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import ev.buan.hostcore.storage.PluginStorageLayout

/**
 * 宿主"存货"的读写：装了谁、谁的开关是开的、各占多少存储、怎么摆成卡片。
 *
 * 从 `HostComposition` 拆出来，因为这一整块**不改变任何运行状态**——它只是把已经存在的
 * 事实读出来拼成 `HostState`。总装类里留着的每个字段都应回答"谁在管这件事"，
 * 而"这一屏显示什么"和"插件怎么起来"是两件不同的事。
 *
 * 唯一有副作用的是 [pruneStorage]：删掉已经不在安装清单里的插件数据。
 * 它只能在这一轮扫描**没有错误**时调用（错误可能意味着扫描漏掉了谁，那它的数据必须留着），
 * 所以判定留在调用方，这一层只负责删。
 */
internal class HostInventory(
    private val context: android.content.Context,
    private val permissions: PermissionStore,
    private val capabilityTrust: CapabilityTrustStore,
    private val preferences: SharedPreferences,
    private val failureScores: FailureScoreStoreImpl,
    private val hasRollback: (String) -> Boolean,
) {

    fun isEnabled(id: String): Boolean = preferences.getBoolean(key(id), true)

    /**
     * Bytes used by one plugin's persistent storage; a broken area reads as zero on the card.
     *
     * Management path: no PermissionService, so a disabled or unprivileged plugin still reports its
     * usage and can be cleared.
     */
    fun storageUsage(id: String): Long =
        runCatching { PluginStorageImpl(context, id).usageBytes() }.getOrDefault(0L)

    /**
     * 把安装清单摆成管理页要的卡片。
     *
     * [instanceOf] 由调用方给：它要问加载器"这个插件现在有没有活着的实例"，而那是宿主的
     * 私有能力（`modules.peek`）。其余一切都只是把已存下来的事实读出来。
     */
    fun cards(
        installed: Collection<InstalledPlugin>,
        failures: Map<String, String>,
        instanceOf: (String) -> Plugin<*>?,
    ): List<PluginCard> = installed.map { plugin ->
        val instance = instanceOf(plugin.id)
        val surface = instance as? AgentSurface
        PluginCard(
            id = plugin.id,
            version = plugin.version,
            apiVersion = plugin.apiVersion,
            signed = plugin.signed,
            trust = plugin.trust,
            enabled = isEnabled(plugin.id),
            title = surface?.title ?: plugin.id,
            body = surface?.body.orEmpty(),
            ui = instance as? AgentUi,
            error = failures[plugin.id],
            requestedPermissions = plugin.requestedPermissions,
            grantedPermissions = permissions.granted(plugin.id),
            storageBytes = storageUsage(plugin.id),
            shortcuts = plugin.shortcuts,
            trustsUpdates = capabilityTrust.trustsUpdates(plugin.id),
            failureCount = failureScores.score(plugin.id).count,
            hasRollback = hasRollback(plugin.id),
        )
    }

    /**
     * Deletes storage of plugins that are no longer installed.
     *
     * [keepIds] 要包含"还在等未签名审批"的那些：它们仍在磁盘上，只是还没被加载，
     * 数据必须活到用户真的卸载为止。
     */
    fun pruneStorage(keepIds: Set<String>) {
        // Renames ride first: without this a renamed plugin id reads as an uninstall, and the
        // prune below would delete the user's history instead of carrying it across.
        PluginStorageLayout.migrateRenames(
            java.io.File(context.filesDir, PluginStorageLayout.ROOT_DIRECTORY),
            RENAMED_PLUGIN_IDS,
        )
        PluginStorageLayout.migrateRenames(
            java.io.File(context.cacheDir, PluginStorageLayout.ROOT_DIRECTORY),
            RENAMED_PLUGIN_IDS,
        )
        PluginStorageLayout.pruneUnknown(
            java.io.File(context.filesDir, PluginStorageLayout.ROOT_DIRECTORY),
            keepIds,
        )
        PluginStorageLayout.pruneUnknown(
            java.io.File(context.cacheDir, PluginStorageLayout.ROOT_DIRECTORY),
            keepIds,
        )
    }

    private fun key(id: String): String = "enabled:$id"

    private companion object {
        /**
         * Plugin id renames whose storage areas survive the rename: on the first sync after the
         * renamed jar lands, the old id's directory becomes the new id's (see
         * [PluginStorageLayout.migrateRenames]). eta-ui became ev-ui with the EV-UI refactor.
         */
        val RENAMED_PLUGIN_IDS = mapOf("ev.buan.plugins.eta.ui" to "ev.buan.plugins.ev.ui")
    }
}
