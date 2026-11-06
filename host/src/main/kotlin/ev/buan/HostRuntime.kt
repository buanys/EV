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
    /** ADR-0008：未签名插件的能力面与「信任后续版本」开关。默认全部关闭。 */
    private val capabilityTrust = CapabilityTrustStore(context)
    /** 本次进程完成启动时用的范围；null 表示还没成功启动过。 */
    @Volatile
    private var startedScope: LaunchScope? = null

    /**
     * 只有调试包才自动信任未签名插件。
     *
     * 这不是"为了省事"，是因为插件的信任绑定在 jar 的 sha256 上：重构期间每重编一次插件，
     * 哈希就变一次，于是每改一行代码都要在管理页点十几次"信任"。开发时这个成本高到会让人
     * 关掉确认，而关掉确认的副作用会跟着代码一起被打包进正式版。
     *
     * 所以把它绑在 FLAG_DEBUGGABLE 上：调试包丢进目录即可跑，正式包仍然是"导入 + 逐个确认"。
     * release 构建里这个值恒为 false，不存在"忘记改回来"。
     */
    private val autoTrustUnsigned =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val installer = PluginInstaller(
        context,
        trust,
        approvals,
        permissions,
        autoTrustUnsigned = autoTrustUnsigned,
        capabilityTrust = capabilityTrust,
    )

    /** Crash marker under `files/crash/`; tells [start] whether this is a safe-mode launch. */
    /** 运维：信任与授权。只改存储，交回快照（见 ADR-0002）。 */
    private val ops = HostOps(
        approvals = approvals,
        capabilityTrust = capabilityTrust,
        writeEnabled = { id, enabled -> preferences.edit().putBoolean(key(id), enabled).apply() },
        writePermission = { id, permission, granted ->
            if (granted) permissions.grant(id, permission) else permissions.revoke(id, permission)
        },
        resync = { resync() },
        reload = { id ->
            // 和原来的 setPermission 同一条规则：停用中、或处于安全模式，都不许把它拉起来。
            // 放在 IO 上也和原来一致——重载会走类加载，不该占着 Compose 线程。
            withContext(Dispatchers.IO) {
                val plugin = installed[id]
                if (plugin != null && isEnabled(id) && safeMode == null) {
                    deactivate(id)
                    activate(plugin)
                }
            }
        },
        onApproved = { id, sha256 ->
            pendingUnsigned = pendingUnsigned.filterNot { it.id == id && it.sha256 == sha256 }
        },
        snapshot = { snapshot() },
    )

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

    /** Declarations plugins made about themselves; empty until each one activates. */
    private val pluginManifests = ConcurrentHashMap<String, org.cordis.api.PluginManifest>()

    /** Settings handlers, for the plugins that let the host change their configuration. */
    private val pluginSettings = ConcurrentHashMap<String, org.cordis.api.PluginSettings>()
    private val failures = linkedMapOf<String, String>()
    private var syncErrors: List<String> = emptyList()
    private var pendingUnsigned: List<PendingUnsigned> = emptyList()
    private var appearanceHandle: org.cordis.EffectHandle? = null
    private var navigationHandle: org.cordis.EffectHandle? = null
    private var directoryHandle: org.cordis.EffectHandle? = null

    /** The host directory, kept so the theme catalog can be resolved outside composition setup. */
    private var serviceDirectory: ServiceDirectoryImpl? = null
    private var pageReaderHandle: org.cordis.EffectHandle? = null
    private var installHandle: org.cordis.EffectHandle? = null
    private var restartHandle: org.cordis.EffectHandle? = null
    private var deviceContextHandle: org.cordis.EffectHandle? = null
    private var screenHandle: org.cordis.EffectHandle? = null
    private var hudHandle: org.cordis.EffectHandle? = null

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

    private val progressHolder = MutableStateFlow(LoadProgress())

    /** The Compose state as a Flow, so non-Compose code can wait on it. */
    fun stateFlow(): kotlinx.coroutines.flow.Flow<HostState?> =
        androidx.compose.runtime.snapshotFlow { stateHolder.value }

    /** Live activation progress; the launch intro observes it, nothing else needs to. */
    val loadProgress: StateFlow<LoadProgress> = progressHolder.asStateFlow()

    /**
     * Plugin whose page or inline UI the host last handed to a screen (recorded in [pageFor] and
     * [uiFor]). It is only used to attribute the crash report: every plugin runs in this process
     * and the host does no in-process isolation, so this is a hint and never a verdict.
     */
    @Volatile
    var lastShownPluginId: String? = null
        private set

    /** 页面归属；只读，依赖以函数传入（见 ADR-0002）。 */
    private val routing = HostRouting(
        modules = modules,
        preferences = preferences,
        pluginIds = { installed.keys },
        isEnabled = { id -> isEnabled(id) },
        failureOf = { id -> failures[id] },
        onShown = { id -> lastShownPluginId = id },
    )

    /**
     * Non-null while this process runs in safe mode: [start] found a crash marker, so no plugin was
     * loaded. [exitSafeMode] removes the marker and loads them again.
     */
    @Volatile
    var safeMode: SafeModeNotice? = null
        private set

    suspend fun start(scope: LaunchScope = LaunchScope.Full): HostState = withContext(Dispatchers.IO) {
        // 已经全量启动过：重复调用（旋转屏幕、从插件页返回）不该再跑一遍同步。
        if (startedScope is LaunchScope.Full) return@withContext snapshot()
        // 之前只按快捷方式加载了闭包，现在用户打开了主界面：补齐，而不是重头再来。
        if (startedScope is LaunchScope.Closure && scope is LaunchScope.Full) {
            startedScope = LaunchScope.Full
            syncAndActivate(LaunchScope.Full)
            lifecycle.completeStart(safeMode = safeMode != null)
            progressHolder.value = progressHolder.value.copy(finished = true, current = null)
            return@withContext snapshot()
        }
        // A marker means the previous process died without unwinding. Compose has no error boundary,
        // so nothing in this process can repair that: this launch runs without loading any plugin,
        // and the management page offers the explicit way out. Read on every start because the
        // marker is the single source of truth for "the last process crashed".
        safeMode = crash.pending()?.let { SafeModeNotice(it.suspectedPluginId, it.recordedAt, it.report) }
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
        if (pageReaderHandle == null) {
            // 离屏浏览器：插件抓不到静态内容时，由宿主用 WebView 渲染再取正文。
            // 这里只在需要时才起 WebView，服务本身不持有任何实例。
            pageReaderHandle = cordis.provide(
                org.cordis.api.PageReader.Key,
                HostPageReader(context) { (context as? EvApplication)?.currentActivity() },
            ).also { runCatching { it.awaitReady() } }
        }
        if (directoryHandle == null) {
            // Concurrent by design: consumers read from the thread they run on (frontend's Compose
            // thread, agent-brain's IO dispatcher) while activation publishes and retracts on its own
            // coroutine. See [ServiceDirectoryImpl] for why a plain map was not enough.
            val directory = ServiceDirectoryImpl()
            // The catalog is host-owned: the UI asks it, never a plugin directly, so disabling the
            // theme plugin degrades to the built-in look instead of leaving the app unthemed.
            directory.publish(org.cordis.api.ThemeCatalogService.Key, HostThemeCatalog(directory))
            directory.publish(
                org.cordis.api.PluginCatalogService.Key,
                HostPluginCatalog({ pluginManifests.values }, { pluginSettings[it] }),
            )
            directory.publish(
                org.cordis.api.PluginManagerService.Key,
                HostPluginManager(
                    this@HostRuntime,
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.Dispatchers.Main.immediate +
                            kotlinx.coroutines.SupervisorJob(),
                    ),
                ),
            )
            // Pages are handed to whichever surface owns a navigation stack, so a plugin page can
            // be composed in place instead of pushing a second Activity (and a second transition).
            directory.publish(
                org.cordis.api.PluginPageService.Key,
                object : org.cordis.api.PluginPageService {
                    override fun pageOf(pluginId: String) = this@HostRuntime.pageFor(pluginId)
                },
            )
            serviceDirectory = directory
            directoryHandle = cordis.provide(ServiceDirectory.Key, directory)
                .also { runCatching { it.awaitReady() } }
        }
        if (installHandle == null) {
            // 安装契约：插件把容器字节交给宿主装上去（forge 的产物走这条路，不再靠人工拷文件）。
            installHandle = cordis.provide(PluginInstallService.Key, InstallService())
                .also { runCatching { it.awaitReady() } }
            deviceContextHandle = cordis.provide(DeviceContextService.Key, DeviceContextServiceImpl(context))
                .also { runCatching { it.awaitReady() } }
            screenHandle = cordis.provide(ScreenService.Key, ScreenServiceImpl(context))
                .also { runCatching { it.awaitReady() } }
            hudHandle = cordis.provide(org.cordis.api.HudService.Key, HudServiceImpl { serviceDirectory })
                .also { runCatching { it.awaitReady() } }
            restartHandle = cordis.provide(HostRestart.Key, HostRestartImpl(context))
                .also { runCatching { it.awaitReady() } }
        }
        syncAndActivate(scope)
        startedScope = scope
        // Only a pass that actually ran outside safe mode counts. Safe mode loaded no plugin, and a
        // start that throws never reaches this line, so both leave the process unable to render a
        // plugin page - which is exactly what PluginPageActivity needs to know.
        lifecycle.completeStart(safeMode = safeMode != null)
        progressHolder.value = progressHolder.value.copy(finished = true, current = null)
        snapshot()
    }

    /**
     * Re-runs the install pass and re-evaluates activation, without restarting the process.
     *
     * This is what makes an install take effect **in the same launch**: sync used to run exactly
     * once, in [start], so a container that arrived while the process was alive was copied to
     * private storage and then sat there until the next launch. The install contract needs the
     * second half of that sentence.
     */
    suspend fun resync(): HostState = withContext(Dispatchers.IO) {
        syncAndActivate(startedScope ?: LaunchScope.Full)
        snapshot()
    }

    /**
     * The single pass both [start] and [resync] run: scan → publish → order → activate.
     *
     * Kept as one function on purpose. Two callers meant two chance for the rules to drift, and the
     * rules here are exactly the ones a plugin author will blame the host for ("装完为什么不生效").
     */
    private suspend fun syncAndActivate(scope: LaunchScope = LaunchScope.Full) {
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
        // Progress covers exactly the plugins this pass will try to activate, in activation order.
        val targets = if (safeMode == null) {
            installed.values.filter { isEnabled(it.id) && scopeCovers(scope, it.id) }
        } else {
            emptyList()
        }
        var processed = 0
        fun publishProgress(current: String?) {
            progressHolder.value = progressHolder.value.copy(
                total = targets.size,
                done = processed,
                current = current,
                names = targets.map { shortPluginName(it.id) },
                failed = targets.filter { it.id in failures }.map { shortPluginName(it.id) }.toSet(),
                finished = false,
            )
        }
        publishProgress(targets.firstOrNull()?.let { shortPluginName(it.id) })
        installed.values.forEach { plugin ->
            when {
                safeMode != null -> {
                    // The user's enabled preference is left untouched: safe mode is this process's
                    // decision, not a persistent setting. The card explains why nothing runs.
                    deactivate(plugin.id)
                    if (isEnabled(plugin.id)) failures[plugin.id] = SAFE_MODE_BLOCKED
                }
                isEnabled(plugin.id) && scopeCovers(scope, plugin.id) -> {
                    activate(plugin)
                    processed++
                    publishProgress(targets.getOrNull(processed)?.let { shortPluginName(it.id) })
                }
                else -> {
                    deactivate(plugin.id)
                    // 停用之后不该再带着"安全模式拦住它"的解释：那条状态描述的是一次加载失败，
                    // 而用户已经明确说了不要它。
                    if (failures[plugin.id] == SAFE_MODE_BLOCKED) failures.remove(plugin.id)
                }
            }
        }
        // Last pass: an enabled plugin that is neither active nor failed is the one state the card
        // used to show as a healthy row. Say it out loud instead - before this, "enabled but never
        // imported" looked exactly like "running fine" (see the agent-prompt case).
        installed.values.forEach { plugin ->
            if (isEnabled(plugin.id) && plugin.id !in active && plugin.id !in failures) {
                failures[plugin.id] = NOT_LOADED
                android.util.Log.e("EV", "plugin ${plugin.id} enabled but not loaded")
            }
        }
    }

    /**
     * Records the user's approval for one unsigned container, bound to this exact hash.
     * Takes effect on the next launch: sync runs before the loader, so a hot reload would have to
     * re-run the whole install pass.
     */
    /**
     * The theme for [id], from whatever theme plugin is active. Falls back to the built-in default
     * when no theme plugin is installed, which is the shipped state: themes are opt-in.
     */
    fun themeSpec(id: String): ev.buan.ui.EvThemeSpec =
        serviceDirectory
            ?.let { directory -> runCatching { directory.lookup(org.cordis.api.ThemeCatalogService.Key)?.resolve(id) }.getOrNull() }
            ?: ev.buan.ui.EvThemeSpec.Default

    fun updateAppearance(transform: ev.buan.ui.EvAppearance.() -> ev.buan.ui.EvAppearance) =
        appearanceStore.update { current -> current.transform() }

    /** Smallest possible routing helper so screens do not carry Activity plumbing. */
    fun openActivity(target: Class<*>) {
        runCatching {
            context.startActivity(android.content.Intent(context, target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun approveUnsigned(id: String, sha256: String): HostState = ops.approveUnsigned(id, sha256)

    fun revokeUnsignedApproval(id: String): HostState = ops.revokeUnsignedApproval(id)

    fun setTrustUpdates(id: String, trust: Boolean): HostState = ops.setTrustUpdates(id, trust)

    /**
     * Enables or disables one plugin for this and future launches. While in safe mode the preference
     * is still recorded, but nothing is loaded until [exitSafeMode].
     */
    suspend fun setEnabled(id: String, enabled: Boolean): HostState = ops.setEnabled(id, enabled)

    suspend fun setPermission(id: String, permission: HostPermission, granted: Boolean): HostState =
        ops.setPermission(id, permission, granted)

    suspend fun uninstall(id: String): HostState = withContext(Dispatchers.IO) {
        deactivate(id)
        installed.remove(id)
        failures.remove(id)
        runCatching { installer.removeExternal(id) }
        // 私有镜像一并删掉：只删外部那份会让"已卸载"的容器继续占着几十 MB，
        // 而且下次扫描才被 prune——中间这段时间它在插件页里像个幽灵。
        installer.removePrivate(id)
        preferences.edit().remove(key(id)).apply()
        // ADR-0008 决策 2：卸载 = 撤回整条链。只清字节批准会让能力面和 opt-in 留下来，
        // 同名插件重新装回来时白拿一次自动通过。
        approvals.revoke(id)
        capabilityTrust.forget(id)
        snapshot()
    }

    /**
     * 安装契约的宿主实现：搬字节 → 触发一次同步 → 把结论翻译成契约形状。
     *
     * 这里刻意不做判断：装得成不成由 [InstallDecision] 决定（那部分在 `:host-core` 里可测），
     * 校验由既有的 `PluginValidator` 决定。本类只负责两件宿主独占的事——写进 `EV/mods`、
     * 以及把这一轮扫描的结果原样交出去。
     */
    private inner class InstallService : PluginInstallService {

        override suspend fun install(request: PluginInstallRequest): PluginInstallResult = withContext(Dispatchers.IO) {
            val sidecar = runCatching { JSONObject(request.sidecarJson) }.getOrNull()
            val declaredId = sidecar?.optString("id")?.takeIf { it.isNotBlank() }
            val entryClass = sidecar?.optString("entryClass")?.takeIf { it.isNotBlank() }
            val version = sidecar?.optString("version")?.takeIf { it.isNotBlank() }

            // 形状不对就先拒绝，不碰磁盘：一个 id 不合法的容器写进 mods 只会在下一轮扫描里变成噪音。
            val early = InstallDecision.decide(
                declaredId = declaredId,
                entryClass = entryClass,
                acceptedIds = emptySet(),
                pending = emptyMap(),
                errors = emptyList(),
                active = false,
            )
            if (early.status == InstallStatus.REJECTED && early.code == InstallDecision.CODE_METADATA) {
                return@withContext early.toContract(version = version)
            }
            val id = declaredId!!

            // 落盘：宿主的外部 mods 目录，jar 与 sidecar 同名，扫描才找得到这一对。
            val staging = File(context.cacheDir, "install-staging").apply { mkdirs() }
            val jarFile = File(staging, "$id.jar")
            val jsonFile = File(staging, "$id.json")
            runCatching {
                jarFile.writeBytes(request.jar)
                jsonFile.writeText(request.sidecarJson)
            }.onFailure { error ->
                return@withContext InstallOutcome(
                    status = InstallStatus.REJECTED,
                    pluginId = id,
                    code = "WRITE_FAILED",
                    message = "写入暂存区失败：${error.message ?: error::class.simpleName}",
                ).toContract(version = version)
            }
            val importedJar = installer.importExternal(jarFile, "$id.jar", overwrite = true)
            val importedJson = installer.importExternal(jsonFile, "$id.json", overwrite = true)
            runCatching { jarFile.delete(); jsonFile.delete() }
            if (importedJar == null || importedJson == null) {
                return@withContext InstallOutcome(
                    status = InstallStatus.REJECTED,
                    pluginId = id,
                    code = "MODS_UNWRITABLE",
                    message = "无法写入 EV/mods：给宿主共享存储权限后再试",
                ).toContract(version = version)
            }

            // 调用方只应在用户对**这一份字节**点头之后传 true；哈希在这里现算，不信调用方给的。
            val sha = androidModuleHash(importedJar)
            if (request.approveUnsigned) approvals.approve(id, sha)

            // 只重扫 + 只激活这一颗，不走 resync()。
            //
            // resync() 会顺手停掉"这一轮没扫到"的插件。而安装的调用方本身就是一颗正在 apply()
            // 的插件：一旦它在这轮里被判为不可用，宿主就 dispose 掉它的 Fiber，调用方的协程
            // 当场被取消，安装流程断在半路（实测 JobCancellationException）。
            // 安装语义不需要动别人，就别动别人。
            val sync = installer.sync()
            syncErrors = sync.errors
            pendingUnsigned = sync.pending
            installed.clear()
            sync.plugins.forEach { installed[it.id] = it }
            if (sync.errors.isEmpty()) pruneStorageOfUninstalled()
            val fresh = installed[id]
            if (fresh != null && isEnabled(id) && safeMode == null) activate(fresh)

            val outcome = InstallDecision.decide(
                declaredId = id,
                entryClass = entryClass,
                acceptedIds = installed.keys.toSet(),
                pending = sync.pending.associate { it.id to it.sha256 },
                errors = sync.errors.map { error ->
                    error.substringBefore(':', missingDelimiterValue = "") to error
                },
                active = active.containsKey(id),
            )
            outcome.toContract(version = version)
        }

        override suspend fun uninstall(pluginId: String): Boolean = withContext(Dispatchers.IO) {
            val known = installed.containsKey(pluginId) || pendingUnsigned.any { it.id == pluginId }
            if (!known) return@withContext false
            this@HostRuntime.uninstall(pluginId)
            true
        }
    }

    /** host-core 的结论 → 契约形状。两边 enum 刻意分开，映射只发生在这一个地方。 */
    private fun InstallOutcome.toContract(version: String?): PluginInstallResult = PluginInstallResult(
        status = when (status) {
            InstallStatus.INSTALLED -> PluginInstallStatus.INSTALLED
            InstallStatus.NEEDS_APPROVAL -> PluginInstallStatus.NEEDS_APPROVAL
            InstallStatus.REJECTED -> PluginInstallStatus.REJECTED
        },
        pluginId = pluginId,
        version = version,
        sha256 = sha256,
        code = code,
        message = message,
        active = active,
    )

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
     * An explicit choice wins; otherwise the EV UI plugin, otherwise the first page plugin in load
     * order. Keeping this in the host means a broken home plugin degrades to the fallback screen
     * instead of leaving the user with no way into management.
     */
    fun homePage(): org.cordis.api.AgentPage? = routing.homePage()

    fun setHomePage(id: String?) = routing.setHomePage(id)

    fun pageFor(id: String): org.cordis.api.AgentPage? = routing.pageFor(id)

    fun uiFor(id: String): AgentUi? = routing.uiFor(id)

    fun titleFor(id: String): String = routing.titleFor(id)

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

    /**
     * 从「只加载了某个快捷方式的闭包」补到全量。
     *
     * 主界面在快捷方式之后被打开时必须调它一次，否则聊天页面对着一堆没激活的插件。
     * 幂等：已经是全量、或者处于安全模式，直接返回。
     */
    suspend fun ensureFullActivation() {
        if (safeMode != null) return
        if (startedScope is LaunchScope.Full) return
        withContext(Dispatchers.IO) {
            startedScope = LaunchScope.Full
            syncAndActivate(LaunchScope.Full)
        }
    }

    /** 这个 id 是否落在此次启动范围内。 */
    private fun scopeCovers(scope: LaunchScope, id: String): Boolean = when (scope) {
        LaunchScope.Full -> true
        is LaunchScope.Closure -> id in closureOf(scope.root)
    }

    /** [root] 的依赖闭包（含它自己）。环会被 [linkedSetOf] 挡住。 */
    private fun closureOf(root: String): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(id: String) {
            if (!result.add(id)) return
            installed[id]?.dependencies?.forEach { visit(it) }
        }
        visit(root)
        return result
    }

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
        // The sync pass already hashed these bytes and the private copy is verified across the copy
        // and locked read-only afterwards, so re-reading every container here (27 MB twice over)
        // only added to startup time. A private copy that somehow changed still fails the loader's
        // own expectedSha256 check, because it is compared against this value.
        val hash = plugin.sha256
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
            // A declaration is recorded before the plugin can fail later: the point of the manifest
            // is to describe the container, and it stays true even if the plugin then throws.
            (instance as? org.cordis.api.PluginSettings)?.let { pluginSettings[plugin.id] = it }
            (instance as? org.cordis.api.ManifestProvider)?.manifest?.let { declared ->
                if (declared.id == plugin.id) pluginManifests[plugin.id] = declared
            }
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
                .isolate(UserDataStorage.Key)
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
            // The user's own data (conversations, keys, memories) lives in the host's tree, not in the
            // plugin area: same gate, but nothing in the plugin lifecycle deletes it.
            provideUserDataIfDeclared(
                pluginContext,
                plugin.requestedPermissions,
                storage = { UserDataStorageImpl(context, plugin.id, permissionService) },
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
            // PENDING is the state a fiber is born in and only a started transition leaves. An
            // inject list that was never provided leaves it exactly there: apply() is skipped,
            // await() returns immediately, and activation would otherwise "succeed" with a plugin
            // that registered nothing - which is how agent-shell lost run_shell without a trace.
            if (fiber.state == org.cordis.FiberState.PENDING) {
                error("插件没有启动：inject 声明的服务没有被提供，apply 从未执行")
            }
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
        // Renames ride first: without this a renamed plugin id reads as an uninstall, and the
        // prune below would delete the user's history instead of carrying it across.
        PluginStorageLayout.migrateRenames(
            File(context.filesDir, PluginStorageLayout.ROOT_DIRECTORY),
            RENAMED_PLUGIN_IDS,
        )
        PluginStorageLayout.migrateRenames(
            File(context.cacheDir, PluginStorageLayout.ROOT_DIRECTORY),
            RENAMED_PLUGIN_IDS,
        )
        // A plugin waiting for unsigned approval is still on disk, just not loaded yet. Its data must
        // survive until the user uninstalls it; only a plugin that is truly gone may be pruned.
        val installedIds = installed.keys + pendingUnsigned.map { it.id }
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
                shortcuts = plugin.shortcuts,
                trustsUpdates = capabilityTrust.trustsUpdates(plugin.id),
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

        /**
         * Card text for an enabled plugin that the loader never produced an instance for.
         *
         * This is the catch-all that was missing: before it, "never imported" and "running fine"
         * rendered identically, and the only way to tell them apart was to read the host's code.
         */
        const val NOT_LOADED = "已启用但未加载：导入阶段没有返回实例（详见 logcat 的 EV 标签）"

        /**
         * Plugin id renames whose storage areas survive the rename: on the first sync after the
         * renamed jar lands, the old id's directory becomes the new id's (see
         * [PluginStorageLayout.migrateRenames]). eta-ui became ev-ui with the EV-UI refactor.
         */
        val RENAMED_PLUGIN_IDS = mapOf("ev.buan.plugins.eta.ui" to "ev.buan.plugins.ev.ui")
    }
}
