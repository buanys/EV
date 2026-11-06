package ev.buan

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.cordis.Context as CordisContext
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.ServiceKey
import org.cordis.api.AgentSurface
import org.cordis.api.AppearanceService
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.cordis.api.AgentUi
import org.cordis.api.BackgroundService
import org.cordis.api.HostPackages
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage
import org.cordis.api.UserDataStorage
import org.cordis.api.ServiceDirectory
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.api.PLUGIN_API_VERSION
import org.cordis.api.DeviceContextService
import org.cordis.api.ScreenService
import org.cordis.api.HostRestart
import org.cordis.api.PluginInstallRequest
import org.cordis.api.PluginInstallResult
import org.cordis.api.PluginInstallService
import org.cordis.api.PluginInstallStatus
import ev.buan.hostcore.install.InstallDecision
import ev.buan.hostcore.install.InstallOutcome
import ev.buan.hostcore.install.InstallStatus
import org.json.JSONObject
import ev.buan.hostcore.lifecycle.HostLifecycle
import ev.buan.hostcore.storage.PluginStorageLayout
import ev.buan.hostcore.validation.HostPolicy
import ev.buan.hostcore.validation.PluginValidator
import ev.buan.hostcore.validation.ValidationResult
import org.cordis.loader.AndroidModuleVerifier
import org.cordis.plugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plugin activation progress for the launch intro. Only the enabled plugins that are activated in
 * one pass are counted; [names] keeps their activation order so the UI can match index to plugin.
 */
data class LoadProgress(
    val total: Int = 0,
    val done: Int = 0,
    val current: String? = null,
    val names: List<String> = emptyList(),
    val failed: Set<String> = emptySet(),
    val finished: Boolean = false,
)

/** Short display name for a plugin id, e.g. `ev.buan.plugins.ev.ui` -> `ev.ui`. */
internal fun shortPluginName(id: String): String = id.removePrefix("ev.buan.plugins.")

/** What the UI renders for one plugin, whether or not it managed to activate. */
data class PluginCard(
    val id: String,
    val version: String,
    val apiVersion: Int,
    val signed: Boolean,
    val enabled: Boolean,
    val title: String,
    val body: String,
    val ui: AgentUi?,
    val error: String?,
    /** Capabilities the container declared in its metadata. */
    val requestedPermissions: Set<HostPermission> = emptySet(),
    /** Of [requestedPermissions], the ones the user currently grants. */
    val grantedPermissions: Set<HostPermission> = emptySet(),
    /** Bytes used by the plugin's persistent storage area. */
    val storageBytes: Long = 0,
    /** 这个插件声明了哪些桌面入口；空表示它不提供快捷方式。 */
    val shortcuts: List<ShortcutDeclaration> = emptyList(),
    /**
     * 是否为这个未签名插件开启了「信任后续版本」（ADR-0008）。
     *
     * 关闭时它已有的字节批准仍然有效，只是下一次更新不再自动继承。
     */
    val trustsUpdates: Boolean = false,
)

data class HostState(
    val cards: List<PluginCard>,
    val errors: List<String>,
    /** Unsigned containers waiting for the user to approve this exact hash. */
    val pending: List<PendingUnsigned> = emptyList(),
    /**
     * Non-null while this process skipped loading every plugin because the previous run crashed.
     * [HostComposition.exitSafeMode] clears it.
     */
    val safeMode: SafeModeNotice? = null,
)

/**
 * Host-owned [ServiceDirectory]: publish/lookup/retract over a concurrent map.
 *
 * Why this must be thread-safe: the directory is the host mediator between plugins, so the threads
 * do not line up. A lookup runs on whatever thread the consumer happens to sit on - the frontend
 * looks `AgentService` up on the Compose thread for every send, agent-brain looks `ModelService` up
 * on its IO dispatcher - while publish and retract happen on the activation path, i.e. a different
 * coroutine that the management page starts and stops. A plain `linkedMapOf` tolerates none of
 * that: a `put` reshapes the table while another thread is reading, and a concurrent reader can then
 * miss a service that was already published (or, with the older hash-table implementations, spin or
 * corrupt the chain). `ConcurrentHashMap` gives each operation a consistent view without a lock the
 * activation path could hold while a plugin reads.
 *
 * Semantics are unchanged from the previous inline map: the key is [ServiceKey.name] (never the key
 * instance), publish replaces an existing entry, retract removes whatever is stored under that name,
 * and lookup is the same unchecked cast the inline version did - `T : Any` is erased at runtime, so
 * the key name is a convention rather than a type check. Insertion order is not part of the contract
 * and no caller can iterate the map, so losing `linkedMapOf` ordering changes nothing.
 */
