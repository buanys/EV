package ev.buan

import android.content.Context as AndroidContext
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
import org.cordis.api.ServiceDirectory
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.api.PLUGIN_API_VERSION
import ev.buan.hostcore.lifecycle.HostLifecycle
import ev.buan.hostcore.storage.PluginStorageLayout
import ev.buan.hostcore.validation.HostPolicy
import ev.buan.hostcore.validation.PluginValidator
import ev.buan.hostcore.validation.ValidationResult
import org.cordis.loader.AndroidModuleVerifier
import org.cordis.plugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
)

data class HostState(
    val cards: List<PluginCard>,
    val errors: List<String>,
    /** Unsigned containers waiting for the user to approve this exact hash. */
    val pending: List<PendingUnsigned> = emptyList(),
    /**
     * Non-null while this process skipped loading every plugin because the previous run crashed.
     * [HostRuntime.exitSafeMode] clears it.
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
internal class ServiceDirectoryImpl : ServiceDirectory {

    private val entries = ConcurrentHashMap<String, Any>()

    override fun <T : Any> publish(key: ServiceKey<T>, value: T) {
        entries[key.name] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> lookup(key: ServiceKey<T>): T? = entries[key.name] as? T

    override fun retract(key: ServiceKey<*>) {
        entries.remove(key.name)
    }
}

/**
 * Owns plugin lifetime for one process.
 *
 * Sync is restart-scoped by design, but enabling or disabling a single plugin is live: it disposes
 * the Fiber or loads the artifact again without restarting the app.
 */
class HostRuntime(private val context: AndroidContext) {

    private val cordis = CordisContext()
    private val trust = PluginTrust(context)
    private val modules = AndroidModuleLoader(
        context = context,
        verifier = AndroidModuleVerifier { descriptor ->
            // Second opinion at load time: sync already approved these bytes, but the file could
            // have been replaced since - and a revoke must not be bypassed by a loader that skipped
            // the host's activation gate. The kernel only passes the descriptor, so hash it here and
            // look the declaration up from the sync record; any mismatch is a hard refusal.
            val plugin = installed[descriptor.id]
            val decision = PluginValidator.verifyAtLoad(
                id = descriptor.id,
                sha256 = androidModuleHash(descriptor.file),
                signing = trust.signingState(descriptor.file),
                policy = HostPolicy(
                    hostCertificateSha256 = trust.hostCertificateHashes,
                    supportedApiVersion = PLUGIN_API_VERSION,
                    approvedUnsignedHashes = approvals.approved(),
                    installedHashes = installed.mapValues { it.value.sha256 },
                    // The validator only understands wire names; HostPermission stays host-side.
                    grantedPermissions = plugin
                        ?.let { mapOf(it.id to permissions.granted(it.id).mapTo(LinkedHashSet()) { p -> p.name }) }
                        .orEmpty(),
                ),
                requestedPermissions = plugin?.requestedPermissions.orEmpty()
                    .mapTo(LinkedHashSet()) { it.name },
            )
            if (decision is ValidationResult.Rejected) {
                throw SecurityException("${decision.rejection.code}: ${decision.rejection.message}")
            }
        },
    )
    private val approvals = UnsignedApprovalStore(context)
    private val permissions = PermissionStore(context)
    private val installer = PluginInstaller(context, trust, approvals, permissions)

    /** Crash marker under `files/crash/`; tells [start] whether this is a safe-mode launch. */
    private val crash = CrashStore(context)
    private val preferences = context.getSharedPreferences("ev.plugins", AndroidContext.MODE_PRIVATE)
    private val appearanceStore = AppearanceStore(context)

    /** Host-owned theme state. Plugins read it through [AppearanceService]. */
    val appearance: State<ev.buan.ui.EvAppearance> = appearanceStore.appearance

    private val installed = linkedMapOf<String, InstalledPlugin>()

    /**
     * Per-plugin host scope Fiber, not the plugin Fiber: it owns the plugin's child Context (and the
     * permission provider installed on it), so disposing it tears down both. See [activate].
     */
    private val active = linkedMapOf<String, Fiber<Unit>>()
    private val failures = linkedMapOf<String, String>()
    private var syncErrors: List<String> = emptyList()
    private var pendingUnsigned: List<PendingUnsigned> = emptyList()
    private var appearanceHandle: org.cordis.EffectHandle? = null
    private var navigationHandle: org.cordis.EffectHandle? = null
    private var directoryHandle: org.cordis.EffectHandle? = null

    /** Start-pass bookkeeping; the public answer is [hasStarted]. */
    private val lifecycle = HostLifecycle()

    /**
     * Whether a full sync + activation pass finished in this process outside safe mode.
     *
     * A safe-mode launch loads nothing by design and a start that throws never reaches the mark, so
     * [EvApplication.startedRuntime] stays null in both cases: a plugin page restored by the system
     * then goes back to the host screen instead of composing an empty shell. [exitSafeMode] sets it
     * once the plugins really are loaded, so a page opened from the management page after recovery
     * renders without restarting the process. The rule itself lives in [HostLifecycle] (host-core)
     * so it is unit-tested without a device.
     */
    val hasStarted: Boolean get() = lifecycle.hasStarted

    /**
     * State the runtime last published, exposed as Compose state so a screen never renders a
     * snapshot it took before another screen changed the runtime.
     *
     * Exiting safe mode from the management page loads the enabled plugins, and enabling/disabling
     * changes which plugin is the home page. The home screen must reflect that as soon as it is
     * back - without re-running sync or restarting the process. Same pattern as [appearance].
     * Written from the start-up coroutine (IO dispatcher) and from UI callbacks; Compose snapshot
     * state is thread-safe, and readers see the last write.
     */
    private val stateHolder = mutableStateOf<HostState?>(null)

    /** Latest published state; null until [start] finishes its first pass. */
    val state: State<HostState?> = stateHolder

    /**
     * Plugin whose page or inline UI the host last handed to a screen (recorded in [pageFor] and
     * [uiFor]). It is only used to attribute the crash report: every plugin runs in this process
     * and the host does no in-process isolation, so this is a hint and never a verdict.
     */
    @Volatile
    var lastShownPluginId: String? = null
        private set

    /**
     * Non-null while this process runs in safe mode: [start] found a crash marker, so no plugin was
     * loaded. [exitSafeMode] removes the marker and loads them again.
     */
    @Volatile
    var safeMode: SafeModeNotice? = null
        private set

    suspend fun start(): HostState = withContext(Dispatchers.IO) {
        // A marker means the previous process died without unwinding. Compose has no error boundary,
        // so nothing in this process can repair that: this launch runs without loading any plugin,
        // and the management page offers the explicit way out. Read on every start because the
        // marker is the single source of truth for "the last process crashed".
        safeMode = crash.pending()?.let { SafeModeNotice(it.suspectedPluginId, it.recordedAt) }
        // The appearance service must exist before any plugin can inject it.
        if (appearanceHandle == null) {
            appearanceHandle = cordis.provide(
                AppearanceService.Key,
                object : AppearanceService {
                    override val current: ev.buan.ui.EvAppearance get() = appearanceStore.appearance.value
                    override fun update(transform: (ev.buan.ui.EvAppearance) -> ev.buan.ui.EvAppearance) =
                        appearanceStore.update(transform)
                },
            ).also { runCatching { it.awaitReady() } }
        }
        if (navigationHandle == null) {
            navigationHandle = cordis.provide(
                org.cordis.api.HostNavigation.Key,
                object : org.cordis.api.HostNavigation {
                    override fun openPluginManager() = openActivity(PluginsActivity::class.java)
                    override fun openSettings() = openActivity(SettingsActivity::class.java)
                },
            ).also { runCatching { it.awaitReady() } }
        }
        if (directoryHandle == null) {
            // Concurrent by design: consumers read from the thread they run on (frontend's Compose
            // thread, agent-brain's IO dispatcher) while activation publishes and retracts on its own
            // coroutine. See [ServiceDirectoryImpl] for why a plain map was not enough.
            directoryHandle = cordis.provide(ServiceDirectory.Key, ServiceDirectoryImpl())
                .also { runCatching { it.awaitReady() } }
        }
        val sync = installer.sync()
        syncErrors = sync.errors
        pendingUnsigned = sync.pending
        installed.clear()
        sync.plugins.forEach { installed[it.id] = it }
        // A re-sync can drop a plugin (uninstalled from EV/mods) while its Fiber is still alive;
        // stop it before the cleanup below removes the storage it may be writing to.
        active.keys.toList().filterNot { it in installed }.forEach { deactivate(it) }
        // Uninstall cleanup: a clean sync is the only proof that a plugin is gone (an error may
        // just mean this scan missed it), and disabled plugins stay installed, so their data is
        // never touched here. Clearing on disable is deliberately not a thing.
        if (sync.errors.isEmpty()) pruneStorageOfUninstalled()
        installed.values.forEach { plugin ->
            when {
                safeMode != null -> {
                    // The user's enabled preference is left untouched: safe mode is this process's
                    // decision, not a persistent setting. The card explains why nothing runs.
                    deactivate(plugin.id)
                    if (isEnabled(plugin.id)) failures[plugin.id] = SAFE_MODE_BLOCKED
                }
                isEnabled(plugin.id) -> activate(plugin)
                else -> deactivate(plugin.id)
            }
        }
        // Only a pass that actually ran outside safe mode counts. Safe mode loaded no plugin, and a
        // start that throws never reaches this line, so both leave the process unable to render a
        // plugin page - which is exactly what PluginPageActivity needs to know.
        lifecycle.completeStart(safeMode = safeMode != null)
        snapshot()
    }

    /**
     * Records the user's approval for one unsigned container, bound to this exact hash.
     * Takes effect on the next launch: sync runs before the loader, so a hot reload would have to
     * re-run the whole install pass.
     */
    fun updateAppearance(transform: ev.buan.ui.EvAppearance.() -> ev.buan.ui.EvAppearance) =
        appearanceStore.update { current -> current.transform() }

    /** Smallest possible routing helper so screens do not carry Activity plumbing. */
    fun openActivity(target: Class<*>) {
        runCatching {
            context.startActivity(android.content.Intent(context, target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun approveUnsigned(id: String, sha256: String): HostState {
        approvals.approve(id, sha256)
        pendingUnsigned = pendingUnsigned.filterNot { it.id == id && it.sha256 == sha256 }
        return snapshot()
    }

    fun revokeUnsignedApproval(id: String): HostState {
        approvals.revoke(id)
        return snapshot()
    }

    /**
     * Enables or disables one plugin for this and future launches. While in safe mode the preference
     * is still recorded, but nothing is loaded until [exitSafeMode].
     */
    suspend fun setEnabled(id: String, enabled: Boolean): HostState = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean(key(id), enabled).apply()
        val plugin = installed[id]
        if (plugin != null) {
            when {
                !enabled -> {
                    deactivate(id)
                    // The safe-mode notice is a launch policy, not an activation failure; hide it
                    // once the user has explicitly disabled the plugin.
                    if (failures[id] == SAFE_MODE_BLOCKED) failures.remove(id)
                }
                safeMode != null -> {
                    // Recorded for after safe mode; loading is exactly what safe mode forbids.
                    deactivate(id)
                    failures[id] = SAFE_MODE_BLOCKED
                }
                else -> activate(plugin)
            }
        }
        snapshot()
    }

    /**
     * Grants or revokes one permission, then re-evaluates activation on the spot.
     *
     * Revoking stops a running plugin (its next host service call would fail anyway); granting
     * reloads it. The store is written first, so the plugin's own [PermissionService] sees the
     * change even when the reload fails for an unrelated reason.
     */
    suspend fun setPermission(id: String, permission: HostPermission, granted: Boolean): HostState =
        withContext(Dispatchers.IO) {
            if (granted) permissions.grant(id, permission) else permissions.revoke(id, permission)
            val plugin = installed[id]
            // Safe mode also suppresses the reload: granting a permission must not load a plugin.
            if (plugin != null && isEnabled(id) && safeMode == null) {
                deactivate(id)
                activate(plugin)
            }
            snapshot()
        }

    /**
     * Deletes one plugin's stored files (management "清理") and restarts it when enabled, so the
     * plugin starts from an empty area. This and uninstall are the only deletions: disabling keeps
     * the plugin's data.
     */
    suspend fun clearStorage(id: String): HostState = withContext(Dispatchers.IO) {
        val enabledPlugin = installed[id]?.takeIf { isEnabled(id) }
        if (enabledPlugin != null) deactivate(id)
        // Host management path: no PermissionService is passed, so「清理」works regardless of the
        // plugin's grants (usageBytes/clear are deliberately not permission-gated).
        runCatching { PluginStorageImpl(context, id).clear() }
        // Safe mode forbids loading; clearing itself is safe because nothing is running.
        if (enabledPlugin != null && safeMode == null) activate(enabledPlugin)
        snapshot()
    }

    /**
     * Leaves safe mode: removes the crash marker and loads every enabled plugin again.
     *
     * Only the marker is removed; `last.txt` is kept so the crash stays inspectable. If the same
     * plugin crashes the process again, the marker comes back and the following launch enters safe
     * mode again - this is a next-launch recovery path, not in-process supervision.
     */
    suspend fun exitSafeMode(): HostState = withContext(Dispatchers.IO) {
        crash.clearPending()
        safeMode = null
        installed.values.forEach { plugin ->
            if (isEnabled(plugin.id)) activate(plugin) else deactivate(plugin.id)
        }
        // The recovery path completes the same activation pass a normal start does, so from here on
        // the process may serve plugin pages (the management page links to them).
        lifecycle.completeStart(safeMode = false)
        snapshot()
    }

    /**
     * The plugin page used as the app root.
     *
     * An explicit choice wins; otherwise the Eta UI plugin, otherwise the first page plugin in load
     * order. Keeping this in the host means a broken home plugin degrades to the fallback screen
     * instead of leaving the user with no way into management.
     */
    fun homePage(): org.cordis.api.AgentPage? {
        val preferred = preferences.getString(KEY_HOME, null)
        if (preferred != null) {
            pageFor(preferred)?.let { return it }
        }
        pageFor(DEFAULT_HOME_PLUGIN)?.let { return it }
        return installed.keys.firstNotNullOfOrNull { pageFor(it) }
    }

    fun setHomePage(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_HOME) else putString(KEY_HOME, id)
        }.apply()
    }

    /**
     * Plugin that provides a full page, or null when it only renders inline content.
     *
     * A non-null answer means the caller is about to render this plugin, so it also records
     * [lastShownPluginId] for crash attribution.
     */
    fun pageFor(id: String): org.cordis.api.AgentPage? {
        if (!isEnabled(id) || failures.containsKey(id)) return null
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*>
        return (instance as? org.cordis.api.AgentPage)?.also { lastShownPluginId = id }
    }

    /**
     * Live UI of one plugin, or null when it is disabled, failed or has no UI.
     *
     * Also records [lastShownPluginId]; see [pageFor].
     */
    fun uiFor(id: String): AgentUi? {
        if (!isEnabled(id) || failures.containsKey(id)) return null
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*>
        return (instance as? AgentUi)?.also { lastShownPluginId = id }
    }

    fun titleFor(id: String): String {
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*>
        return (instance as? AgentSurface)?.title ?: id
    }

    /**
     * Stops every plugin. The runtime advertises itself as stopped afterwards, so a plugin page
     * restored by the system goes back to the host screen rather than composing a dead shell; a
     * subsequent [start] marks it started again.
     */
    suspend fun dispose() {
        active.keys.toList().forEach { BackgroundSessions.releaseAll(context, it) }
        active.values.forEach { runCatching { it.dispose() } }
        active.clear()
        lifecycle.stop()
    }

    // ---------------------------------------------------------------- activation

    private suspend fun activate(plugin: InstalledPlugin) {
        if (active.containsKey(plugin.id)) return
        // Process-wide gate: even a caller that forgot to check [safeMode] cannot load a plugin.
        // [exitSafeMode] clears the mode before it activates anything, so this never blocks recovery.
        if (safeMode != null) {
            failures[plugin.id] = SAFE_MODE_BLOCKED
            return
        }
        failures.remove(plugin.id)
        // Capability gate before any loading work: an ungranted declaration means the plugin may
        // not run, and the card says why so the user can grant it.
        val missing = PermissionGate.missing(plugin.requestedPermissions, permissions.granted(plugin.id))
        if (missing.isNotEmpty()) {
            failures[plugin.id] = PermissionGate.denialMessage(plugin.id, missing)
            return
        }
        val hash = androidModuleHash(plugin.file)
        val descriptor = AndroidModuleDescriptor(
            id = plugin.id,
            version = plugin.version,
            entryClass = plugin.entryClass,
            file = plugin.file,
            expectedSha256 = hash,
            // Without this the loader never links a plugin to its dependency loader.
            dependencies = plugin.dependencies,
            sharedHostPackages = HostPackages.shared,
        )
        var scopeFiber: Fiber<Unit>? = null
        try {
            modules.register(descriptor)
            val instance = modules.import(modules.moduleUrl(plugin.id), null) as? Plugin<Unit>
                ?: error("入口 ${plugin.entryClass} 未实现 org.cordis.Plugin")
            // One host-owned Fiber per plugin, and the provider is installed on it. Providing from
            // `cordis.extend { isolate(...) }` installs the binding on the *root* Fiber's service
            // snapshot, and the kernel resolver then hands the last provided instance to any plugin
            // that reads the key without declaring `inject` (measured in the T06 spike). A dedicated
            // Fiber keeps the provider reachable only from this plugin's descendants.
            val scope = cordis.plugin(scopePlugin(plugin.id), Unit)
            scopeFiber = scope
            withTimeout(START_TIMEOUT_MS) { scope.await() }
            val pluginContext = scope.ctx
                .isolate(PermissionService.Key)
                .isolate(PluginStorage.Key)
                .isolate(BackgroundService.Key)
            val permissionService = PermissionServiceImpl(plugin.id, permissions)
            pluginContext.provide(PermissionService.Key, permissionService)
            // Storage is lent on the same isolated Context: relative paths can only resolve inside
            // this plugin's own files/plugins/<id> and cache/plugins/<id>. It is declaration-gated
            // here as well: a plugin that never declared PLUGIN_STORAGE gets no binding at all
            // (ctx[PluginStorage.Key] stays null), while the binding handed to a declarer re-checks
            // the grant on every data call. See [providePluginStorageIfDeclared].
            providePluginStorageIfDeclared(
                pluginContext,
                plugin.requestedPermissions,
                storage = { PluginStorageImpl(context, plugin.id, permissionService) },
            )
            // Background execution is host-mediated too: the plugin takes references, the host owns
            // the process-wide foreground service and the notification the user can see.
            pluginContext.provide(
                BackgroundService.Key,
                BackgroundServiceImpl(plugin.id, permissionService, context),
            )
            val fiber = pluginContext.plugin(instance, Unit)
            // A plugin that never finishes applying must not hold the whole host hostage.
            withTimeout(START_TIMEOUT_MS) { fiber.await() }
            active[plugin.id] = scope
        } catch (timeout: TimeoutCancellationException) {
            failures[plugin.id] = "启动超时（${START_TIMEOUT_MS / 1000} 秒）"
            runCatching { scopeFiber?.dispose() }
        } catch (error: Throwable) {
            // The card must show *why*, not just the wrapper type: a reflective loader failure
            // surfaces as InvocationTargetException whose cause names the missing class or method,
            // and that cause is the only actionable part of the report.
            android.util.Log.e("EV", "plugin ${plugin.id} activation failed", error)
            failures[plugin.id] = generateSequence(error) { it.cause }
                .map { "${it.javaClass.name}: ${it.message.orEmpty()}" }
                .joinToString("  ←  ")
            runCatching { scopeFiber?.dispose() }
        }
    }

    /** Empty plugin whose only job is to own the per-plugin scope Fiber. */
    private fun scopePlugin(id: String): Plugin<Unit> = plugin(name = "host/scope/$id") { _, _ -> }

    private suspend fun deactivate(id: String) {
        // A stopped plugin must not keep the host's foreground service alive; its references are
        // dropped before the scope (and therefore the plugin) goes away.
        BackgroundSessions.releaseAll(context, id)
        // The scope Fiber owns the plugin Fiber and both providers; one dispose tears down the whole
        // per-plugin subtree. Storage is deliberately left in place: disabling keeps plugin data.
        active.remove(id)?.let { scope -> runCatching { scope.dispose() } }
        runCatching { modules.release(id) }
    }

    /**
     * Deletes storage of plugins that are no longer installed. Only called after a sync without
     * errors: an error may mean the scan missed a plugin, and its data must survive that.
     */
    private fun pruneStorageOfUninstalled() {
        val installedIds = installed.keys
        PluginStorageLayout.pruneUnknown(
            File(context.filesDir, PluginStorageLayout.ROOT_DIRECTORY),
            installedIds,
        )
        PluginStorageLayout.pruneUnknown(
            File(context.cacheDir, PluginStorageLayout.ROOT_DIRECTORY),
            installedIds,
        )
    }

    /**
     * Bytes used by one plugin's persistent storage; a broken area reads as zero on the card.
     *
     * Management path: no PermissionService, so a disabled or unprivileged plugin still reports its
     * usage and can be cleared.
     */
    private fun storageUsage(id: String): Long =
        runCatching { PluginStorageImpl(context, id).usageBytes() }.getOrDefault(0L)

    // ---------------------------------------------------------------- state

    private fun snapshot(): HostState = HostState(
        cards = installed.values.map { plugin ->
            val instance = runCatching { modules.peek(modules.moduleUrl(plugin.id)) }.getOrNull() as? Plugin<*>
            val surface = instance as? AgentSurface
            PluginCard(
                id = plugin.id,
                version = plugin.version,
                apiVersion = plugin.apiVersion,
                signed = plugin.signed,
                enabled = isEnabled(plugin.id),
                title = surface?.title ?: plugin.id,
                body = surface?.body.orEmpty(),
                ui = instance as? AgentUi,
                error = failures[plugin.id],
                requestedPermissions = plugin.requestedPermissions,
                grantedPermissions = permissions.granted(plugin.id),
                storageBytes = storageUsage(plugin.id),
            )
        },
        errors = syncErrors,
        pending = pendingUnsigned,
        safeMode = safeMode,
    ).also { stateHolder.value = it }

    private fun isEnabled(id: String): Boolean = preferences.getBoolean(key(id), true)

    private fun key(id: String): String = "enabled:$id"

    private companion object {
        /** Upper bound for one plugin's apply(); a stuck plugin must not block the host. */
        const val START_TIMEOUT_MS = 90_000L
        /** Card text while safe mode keeps an enabled plugin unloaded; not an activation failure. */
        const val SAFE_MODE_BLOCKED = "安全模式：上次运行崩溃，本次启动未加载（退出安全模式后重试）"
        const val KEY_HOME = "home.page"
        const val DEFAULT_HOME_PLUGIN = "ev.buan.plugins.eta.ui"
    }
}
