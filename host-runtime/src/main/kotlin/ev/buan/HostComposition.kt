package ev.buan

import android.content.Context as AndroidContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.cordis.Context as CordisContext
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.api.AppearanceService
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.cordis.api.AgentUi
import org.cordis.api.BackgroundService
import org.cordis.api.HostPermission
import org.cordis.api.CredentialService
import org.cordis.api.PermissionService
import org.cordis.api.RuntimeBudgetService
import org.cordis.api.ServiceDirectory
import org.cordis.loader.AndroidModuleLoader
import org.cordis.api.PLUGIN_API_VERSION
import org.cordis.api.DeviceContextService
import org.cordis.api.ScreenService
import org.cordis.api.HostRestart
import org.cordis.api.PluginInstallService
import ev.buan.hostcore.lifecycle.HostLifecycle
import ev.buan.hostcore.storage.PluginStorageLayout
import ev.buan.hostcore.validation.HostPolicy
import ev.buan.hostcore.validation.PluginValidator
import ev.buan.hostcore.validation.ValidationResult
import org.cordis.loader.AndroidModuleVerifier
import org.cordis.plugin
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 导航目标由 app 层注入。 */
class HostNavigationTargets(
    val pluginManager: () -> Unit = {},
    val settings: () -> Unit = {},
)

class HostComposition(private val context: AndroidContext) : ActivationHost {

    /**
     * 由 app 层注入：当前可见的 Activity（离屏渲染取 WebView 容器时要用）与重启后的落点。
     *
     * 运行时不该认识 Activity 类，也不该知道 `MainActivity` 叫什么——那是 :host-app 的事。
     * 拆模块时这两条正好是挡路的依赖，所以先在本模块里把它们变成注入点。
     */
    var activityProvider: () -> android.app.Activity? = { null }

    /** 导航目标与设备上下文服务由 app 层注入：运行时不该认识 Activity / 无障碍服务。 */
    var navigation = HostNavigationTargets()
    var deviceContextFactory: ((AndroidContext) -> org.cordis.api.DeviceContextService)? = null
    /**
     * 屏幕、HUD、后台常驻三组能力的实现在 app 层（它们长在无障碍服务与前台服务上）。
     * 运行时只拿接口，不拿实现——这正是 2f 拆模块最后挡路的三处。
     */
    var screenFactory: ((AndroidContext) -> org.cordis.api.ScreenService)? = null
    var hudFactory: ((() -> ServiceDirectory?) -> org.cordis.api.HudService)? = null
    var backgroundServiceFactory: ((String, PermissionService?, AndroidContext) -> org.cordis.api.BackgroundService)? = null
    var backgroundRelease: (String) -> Unit = {}

    var restartIntent: (android.content.Context) -> android.content.Intent = { ctx ->
        android.content.Intent(
            ctx,
            Class.forName("ev.buan.MainActivity"),
        )
    }

    override val cordis = CordisContext()
    private val trust = PluginTrust(context)
    override val modules = AndroidModuleLoader(
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
                origin = plugin?.origin ?: ev.buan.hostcore.trust.PluginOrigin.EXTERNAL,
            )
            if (decision is ValidationResult.Rejected) {
                throw SecurityException("${decision.rejection.code}: ${decision.rejection.message}")
            }
        },
    )
    private val approvals = UnsignedApprovalStore(context)
    override val permissions = PermissionStore(context)
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

    /**
     * 受限档位能不能在这个进程里跑：与"自动信任未签名"同一个开关。
     *
     * 两者都是"这个包本身已经不可信"的同一件事：可调试图层的安全性归零，隔离插件换不来
     * 任何东西；正式包不使用它，受限档位就真的只能等隔离实现之后再跑。
     */
    override val allowsRestrictedInProcess: Boolean get() = autoTrustUnsigned

    override val installer = PluginInstaller(
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
        stop = { id -> activation.deactivate(id) },
        removeFiles = { id ->
            runCatching { installer.removeExternal(id) }
            installer.removePrivate(id)
        },
        removePreference = { id ->
            preferences.edit().remove(key(id)).apply()
            // 卸载时把失败历史一起清掉：prefs 里留着一个再也对不上号的数字没有意义。
            failureScores.remove(id)
        },
        clearPluginStorage = { id -> runCatching { PluginStorageImpl(context, id).clear() } },
        resync = { resync() },
        reload = { id ->
            // 和原来的 setPermission 同一条规则：停用中、或处于安全模式，都不许把它拉起来。
            // 放在 IO 上也和原来一致——重载会走类加载，不该占着 Compose 线程。
            withContext(Dispatchers.IO) {
                val plugin = installed[id]
                if (plugin != null && inventory.isEnabled(id) && safeMode == null) {
                    activation.deactivate(id)
                    activation.activate(plugin)
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

    /**
     * 激活子系统。它要的那份宿主状态见 [ActivationHost]——把接口放在这里而不是传一串 lambda，
     * 是为了让"激活依赖宿主到什么程度"一眼可见。
     */
    private val activation by lazy { HostActivation(this) }

    override val appContext: AndroidContext get() = context

    override fun isPluginEnabled(id: String): Boolean = inventory.isEnabled(id)

    override fun pruneStorage(keepIds: Set<String>) = inventory.pruneStorage(keepIds)

    override fun makeBudget(pluginId: String, lifetime: Job): RuntimeBudgetService =
        HostRuntimeBudget(pluginId, budgetGate, lifetime)

    override val safeModeNotice: SafeModeNotice? get() = safeMode

    override fun makeBackgroundService(
        pluginId: String,
        permission: PermissionService?,
    ): org.cordis.api.BackgroundService =
        backgroundServiceFactory?.invoke(pluginId, permission, context)
            ?: error("后台常驻服务未注入：请检查 EvApplication 的接线")

    override fun releaseBackground(pluginId: String) = backgroundRelease(pluginId)

    override fun recordScan(
        installedNow: List<InstalledPlugin>,
        errors: List<String>,
        pending: List<PendingUnsigned>,
    ) {
        syncErrors = errors
        pendingUnsigned = pending
        installed.clear()
        installedNow.forEach { installed[it.id] = it }
    }

    override fun reportProgress(targets: List<InstalledPlugin>, processed: Int) {
        progressHolder.value = progressHolder.value.copy(
            total = targets.size,
            done = processed,
            current = targets.getOrNull(processed)?.let { shortPluginName(it.id) },
            names = targets.map { shortPluginName(it.id) },
            failed = targets.filter { it.id in failures }.map { shortPluginName(it.id) }.toSet(),
            finished = false,
        )
    }

    override fun failureScoreOf(id: String): ev.buan.hostcore.recovery.FailureScore = failureScores.score(id)

    override fun isolatedToolCount(id: String): Int = isolated.toolCount(id)

    override suspend fun isolate(plugin: InstalledPlugin): Int =
        isolated.start(plugin, HostPermission.PLUGIN_STORAGE in permissions.granted(plugin.id))

    override suspend fun stopIsolated(id: String) = isolated.stop(id)

    override fun recordFailure(id: String, success: Boolean) {
        val current = failureScores.score(id)
        failureScores.save(id, if (success) current.afterSuccess() else current.afterFailure())
    }

    /** 凭据的存放地。插件拿不到路径，只能经 `CredentialService` 按名字要。 */
    private val credentialStore = CredentialStore(java.io.File(context.filesDir, "secrets"))

    override fun makeCredentials(pluginId: String, permission: PermissionService): CredentialService =
        CredentialServiceImpl(pluginId, credentialStore, permission)

    /** 失败计数：跨进程保留，否则每次启动都重新给三次机会，这道门等于没有。 */
    private val failureScores = FailureScoreStoreImpl(preferences)

    /** 存货：装了谁、开关、占用、卡片。只管读，见 [HostInventory]。 */
    private val inventory = HostInventory(
        context,
        permissions,
        capabilityTrust,
        preferences,
        failureScores,
        hasRollback = { id -> installer.hasRollback(id) },
    )

    /** Host-owned theme state. Plugins read it through [AppearanceService]. */
    val appearance: State<ev.buan.ui.EvAppearance> = appearanceStore.appearance

    override val installed = linkedMapOf<String, InstalledPlugin>()

    /**
     * Per-plugin host scope Fiber, not the plugin Fiber: it owns the plugin's child Context (and the
     * permission provider installed on it), so disposing it tears down both. See [activate].
     */
    override val active = linkedMapOf<String, Fiber<Unit>>()

    /** Declarations plugins made about themselves; empty until each one activates. */
    override val pluginManifests = ConcurrentHashMap<String, org.cordis.api.PluginManifest>()

    /** Settings handlers, for the plugins that let the host change their configuration. */
    override val pluginSettings = ConcurrentHashMap<String, org.cordis.api.PluginSettings>()
    override val failures = linkedMapOf<String, String>()

    /**
     * 受限档的账：谁在子进程里、提供了几个工具、死没死。
     *
     * `directory` 用 lambda 传入而不是传实例：它要到 [start] 里才建出来，而受限插件的激活
     * 发生在它之后——传实例的写法会逼着装配顺序再固定一次。
     */
    private val isolated by lazy {
        IsolatedRegistry(
            context = context,
            directory = { serviceDirectory },
            failures = failures,
            onFailure = { id -> recordFailure(id, success = false) },
            publish = { runCatching { snapshot() } },
        )
    }
    private var syncErrors: List<String> = emptyList()
    override var pendingUnsigned: List<PendingUnsigned> = emptyList()
    private var appearanceHandle: org.cordis.EffectHandle? = null
    private var navigationHandle: org.cordis.EffectHandle? = null
    private var directoryHandle: org.cordis.EffectHandle? = null

    /** The host directory, kept so the theme catalog can be resolved outside composition setup. */
    private var serviceDirectory: ServiceDirectoryImpl? = null
    private var pageReaderHandle: org.cordis.EffectHandle? = null
    /** 离屏渲染实例：内存压力时要能取消它正在跑的那一次。 */
    private var pageReader: HostPageReader? = null

    /**
     * 执行预算的名额表。**全宿主一份**：被抢的是设备的线程与内存，不是某一个插件的。
     * 每插件分别计数只会让总并发放大成"插件数 × 每插件上限"，也就是没有上限。
     */
    private val budgetGate = RuntimeBudgetGate()

    /**
     * 每个插件的预算寿命。停用插件时先取消它，再拆 Fiber——见 [deactivate]。
     * 用显式表而不是 `ctx.effect`：effect 是异步挂载的，插件在挂载之前就能开始干活，
     * 而这张表在 [activate] 交出服务之前就已经填好了。
     */
    override val budgetJobs = ConcurrentHashMap<String, Job>()
    private var installHandle: org.cordis.EffectHandle? = null
    private var restartHandle: org.cordis.EffectHandle? = null
    private var deviceContextHandle: org.cordis.EffectHandle? = null
    private var screenHandle: org.cordis.EffectHandle? = null
    private var hudHandle: org.cordis.EffectHandle? = null

    /** Start-pass bookkeeping; the public answer is [hasStarted]. */
    private val lifecycle = HostLifecycle()

    /** 恢复：安全模式与崩溃标记（见 ADR-0002 "恢复只调用机制"）。 */
    private val recovery = HostRecovery(
        crash = crash,
        lifecycle = lifecycle,
        setSafeMode = { notice -> safeMode = notice },
        applyLaunch = { activation.sync(startedScope ?: LaunchScope.Full) },
        snapshot = { snapshot() },
    )

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
        isEnabled = { id -> inventory.isEnabled(id) },
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
            activation.sync(LaunchScope.Full)
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
                    override fun openPluginManager() = navigation.pluginManager()
                    override fun openSettings() = navigation.settings()
                },
            ).also { runCatching { it.awaitReady() } }
        }
        if (pageReaderHandle == null) {
            // 离屏浏览器：插件抓不到静态内容时，由宿主用 WebView 渲染再取正文。
            // 这里只在需要时才起 WebView，服务本身不持有任何实例。
            pageReaderHandle = cordis.provide(
                org.cordis.api.PageReader.Key,
                HostPageReader(context) { activityProvider() }.also { pageReader = it },
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
                    this@HostComposition,
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
                    override fun pageOf(pluginId: String) = this@HostComposition.pageFor(pluginId)
                },
            )
            // The offscreen renderer is an optional backend: agent-browse keeps its `web_fetch`
            // tool working without it and reports "this page needs JS" instead of pretending the
            // page is empty. It is published here as well as provided on the Cordis context,
            // because a consumer that treats it as optional looks it up here — an optional
            // dependency cannot be an `inject` entry, that one would hang until the start timeout.
            pageReader?.let { directory.publish(org.cordis.api.PageReader.Key, it) }
            serviceDirectory = directory
            directoryHandle = cordis.provide(ServiceDirectory.Key, directory)
                .also { runCatching { it.awaitReady() } }
        }
        if (installHandle == null) {
            // 安装契约：插件把容器字节交给宿主装上去（forge 的产物走这条路，不再靠人工拷文件）。
            installHandle = cordis.provide(
                PluginInstallService.Key,
                HostInstallService(
                    context = context,
                    installer = installer,
                    approvals = approvals,
                    // 落账归宿主，判定归 :host-core，搬运归这个服务——三边各管一件事。
                    rescan = { rescanForInstall() },
                    activateOnly = { id -> installed[id]?.let { activation.activate(it) } },
                    isEnabled = { id -> inventory.isEnabled(id) },
                    inSafeMode = { safeMode != null },
                    activeIds = { active.keys.toSet() },
                    uninstallKnown = { id ->
                        // 宿主只认识"扫到的"和"等批准的"；别的 id 直接拒绝，不去猜是不是拼错了。
                        val known = installed.containsKey(id) || pendingUnsigned.any { it.id == id }
                        if (known) uninstall(id)
                        known
                    },
                ),
            )
                .also { runCatching { it.awaitReady() } }
            deviceContextFactory?.let { factory ->
                deviceContextHandle = cordis.provide(DeviceContextService.Key, factory(context))
                    .also { runCatching { it.awaitReady() } }
            }
            screenFactory?.let { factory ->
                screenHandle = cordis.provide(ScreenService.Key, factory(context))
                    .also { runCatching { it.awaitReady() } }
            }
            hudFactory?.let { factory ->
                hudHandle = cordis.provide(org.cordis.api.HudService.Key, factory { serviceDirectory })
                    .also { runCatching { it.awaitReady() } }
            }
            restartHandle = cordis.provide(HostRestart.Key, HostRestartImpl(context, restartIntent))
                .also { runCatching { it.awaitReady() } }
        }
        activation.sync(scope)
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
    /**
     * 系统内存压力。
     *
     * 宿主几乎不缓存重对象（没有位图缓存，浏览器面板的 WebView 随面板销毁），
     * 唯一可能在压力期间攥着几 MB 的是离屏渲染——所以这里只做一件事：掐掉在飞的那次。
     * 更低的级别（TRIM_MEMORY_UI_HIDDEN 等）不处理：那表示界面不可见，不是内存告急。
     */
    fun handleTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            pageReader?.cancelInFlight()
        }
    }

    suspend fun resync(): HostState = withContext(Dispatchers.IO) {
        activation.sync(startedScope ?: LaunchScope.Full)
        snapshot()
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

    suspend fun uninstall(id: String): HostState = ops.uninstall(id)

    /**
     * 安装路径专用的扫描：只把结果落账，不碰任何插件的活跃状态。
     *
     * 与 [syncAndActivate] 的区别就是这一点。安装的调用方本身是一颗正在 `apply()` 的插件，
     * "这一轮没扫到就停掉"的规则会把它自己停掉（详见 [HostInstallService.rescan]）。
     */
    private suspend fun rescanForInstall(): PluginScanSummary {
        val sync = installer.sync()
        syncErrors = sync.errors
        pendingUnsigned = sync.pending
        installed.clear()
        sync.plugins.forEach { installed[it.id] = it }
        if (sync.errors.isEmpty()) inventory.pruneStorage(installed.keys + pendingUnsigned.map { it.id })
        return PluginScanSummary(installed.keys.toSet(), sync.pending, sync.errors)
    }

    /**
     * Deletes one plugin's stored files (management "清理") and restarts it when enabled, so the
     * plugin starts from an empty area. This and uninstall are the only deletions: disabling keeps
     * the plugin's data.
     */
    suspend fun clearStorage(id: String): HostState = ops.clearStorage(id)

    /**
     * 回滚到上一版容器字节：写回 `EV/mods` 后重扫。
     *
     * 只回退字节，不改 sidecar（外部那份 json 在扫描到新版本时就被覆盖了）。
     * 没有存档时什么也不做，返回当前状态。
     */
    suspend fun rollback(id: String): HostState = withContext(Dispatchers.IO) {
        // 用**外部**那个文件的名字：`file` 是私有副本（<id>.apk），写回 mods 会多出一个没人认识的文件。
        val jarName = installed[id]?.sourceName?.takeIf { it.isNotBlank() } ?: "$id.jar"
        installer.rollback(id, jarName)
        resync()
    }

    fun hasRollback(id: String): Boolean = installer.hasRollback(id)

    /**
     * 重置一个插件的失败计数并重新激活它。
     *
     * 这是"失败计分"唯一的出口：没有它，一个连续失败三次的插件就再也不会被尝试，
     * 用户只能在卸载重装和永远停用之间选。
     */
    suspend fun resetFailureScore(id: String): HostState = withContext(Dispatchers.IO) {
        failureScores.reset(id)
        resync()
    }

    /**
     * Leaves safe mode: removes the crash marker and loads every enabled plugin again.
     *
     * Only the marker is removed; `last.txt` is kept so the crash stays inspectable. If the same
     * plugin crashes the process again, the marker comes back and the following launch enters safe
     * mode again - this is a next-launch recovery path, not in-process supervision.
     */
    suspend fun exitSafeMode(): HostState = recovery.leaveSafeMode()

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
        active.keys.toList().forEach { id -> backgroundRelease(id) }
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
            activation.sync(LaunchScope.Full)
        }
    }






    // ---------------------------------------------------------------- state

    /** 开关存在宿主自己的 prefs 里，键名只在这里拼。 */
    private fun key(id: String): String = "enabled:$id"

    private fun snapshot(): HostState = HostState(
        cards = inventory.cards(
            installed = installed.values,
            failures = failures,
            instanceOf = { id -> runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*> },
            isolatedOf = { id -> isolated.toolCount(id) },
        ),
        errors = syncErrors,
        pending = pendingUnsigned,
        safeMode = safeMode,
    ).also { stateHolder.value = it }

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
