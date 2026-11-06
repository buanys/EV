package ev.buan

import android.content.Context as AndroidContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.cordis.Context as CordisContext
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.api.BackgroundService
import org.cordis.api.HostPackages
import org.cordis.api.PermissionService
import org.cordis.api.PluginManifest
import org.cordis.api.PluginSettings
import org.cordis.api.PluginStorage
import org.cordis.api.RuntimeBudgetService
import org.cordis.api.UserDataStorage
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.plugin

/**
 * 激活子系统需要的宿主状态。
 *
 * 这张接口是整个拆分的关键，也是它的代价：这些字段以前全是 `HostComposition` 的私有成员，
 * 而"谁可以被激活、激活失败怎么记、预算名额归谁"这三件事本来就横跨两个对象。
 * 与其把 15 个 lambda 塞进构造函数，不如把"它需要什么"写成一份契约——读这张接口
 * 就能知道激活依赖宿主到什么程度，以后想再解耦也有据可依。
 *
 * 分成三段：**依赖**（不可变，构造后不变）、**共享状态**（可变，两边都读写）、
 * **副作用**（宿主独有的能力：后台服务与进度上报）。
 */
internal interface ActivationHost {

    // ── 依赖 ────────────────────────────────────────────────────────────────

    val cordis: CordisContext
    val modules: AndroidModuleLoader
    val appContext: AndroidContext
    val permissions: PermissionStore
    val installer: PluginInstaller

    // ── 共享状态（可变）─────────────────────────────────────────────────────

    val installed: MutableMap<String, InstalledPlugin>
    val active: MutableMap<String, Fiber<Unit>>
    val failures: MutableMap<String, String>
    val pluginManifests: MutableMap<String, PluginManifest>
    val pluginSettings: MutableMap<String, PluginSettings>

    /** 每个插件的预算寿命；停用时取消它，在飞的任务随之结束。 */
    val budgetJobs: MutableMap<String, Job>

    /** 这一轮扫描到但还在等未签名审批的容器：它们的存储不能被当成"已卸载"清掉。 */
    val pendingUnsigned: List<PendingUnsigned>

    // ── 决策与副作用 ────────────────────────────────────────────────────────

    /** 非空表示本次启动处于安全模式：一个插件都不许加载。 */
    val safeModeNotice: SafeModeNotice?

    /** 把这一轮扫描的结果落账（`installed` / `syncErrors` / `pendingUnsigned`）。 */
    fun recordScan(installedNow: List<InstalledPlugin>, errors: List<String>, pending: List<PendingUnsigned>)

    /** 报告激活进度；[processed] 是已经尝试过的数量。 */
    fun reportProgress(targets: List<InstalledPlugin>, processed: Int)

    /** 用户是否启用了这个插件（开关存在宿主自己的 prefs 里）。 */
    fun isPluginEnabled(id: String): Boolean

    /** 删掉已经不在安装清单里的插件数据；[keepIds] 之外的都算过时。 */
    fun pruneStorage(keepIds: Set<String>)

    /** 给一个插件造它自己的预算服务；[lifetime] 取消时在飞的任务随之结束。 */
    fun makeBudget(pluginId: String, lifetime: Job): RuntimeBudgetService

    /**
     * 给一个插件造它的凭据服务。宿主自己持有存放地，这里只交出"按名字读写"的窄面——
     * 与 [makeBudget] 同理：接口说能做什么，不说由谁实现。
     */
    fun makeCredentials(pluginId: String, permission: PermissionService): org.cordis.api.CredentialService

    /** 后台常驻服务由 app 层接线；没接就是宿主配置错了，激活会失败并说明原因。 */
    fun makeBackgroundService(pluginId: String, permission: PermissionService?): BackgroundService

    fun releaseBackground(pluginId: String)
}

/**
 * 启动时把插件从"躺在磁盘上"变成"跑起来"的那一套：扫描落账 → 定目标 → 逐个激活 / 停用。
 *
 * 从 `HostComposition` 拆出来的是这个类存在的理由：它回答的是**一个进程怎么把插件装起来**，
 * 而宿主总装回答的是**这个应用由什么组成**。两者以前是同一个对象，于是
 * "装完为什么不生效"这类问题要在 900 行里找答案。
 *
 * 这里集中了三条不变量，它们是这个子系统全部的价值：
 *
 *  1. **安全模式是这一进程的决定**，不写用户偏好：卡片会解释"为什么什么都没跑"。
 *  2. **干净的一轮扫描才能删数据**：扫描报错可能只是这一轮没看见某个插件，
 *     那时它的存储必须留着。
 *  3. **每个插件一颗 Fiber**，提供者装在它上面——否则内核会把最后提供的实例
 *     发给任何读这个 key 的插件（T06 实测）。
 */
internal class HostActivation(private val host: ActivationHost) {

    /**
     * The single pass both `start` and `resync` run: scan → record → order → activate.
     *
     * Kept as one function on purpose. Two callers meant two chances for the rules to drift, and
     * the rules here are exactly the ones a plugin author will blame the host for ("装完为什么不生效").
     */
    suspend fun sync(scope: LaunchScope = LaunchScope.Full) {
        val sync = host.installer.sync()
        host.recordScan(sync.plugins, sync.errors, sync.pending)
        // A re-sync can drop a plugin (uninstalled from EV/mods) while its Fiber is still alive;
        // stop it before the cleanup below removes the storage it may be writing to.
        host.active.keys.toList().filterNot { it in host.installed }.forEach { deactivate(it) }
        // Uninstall cleanup: a clean sync is the only proof that a plugin is gone (an error may
        // just mean this scan missed it), and disabled plugins stay installed, so their data is
        // never touched here. Clearing on disable is deliberately not a thing.
        if (sync.errors.isEmpty()) {
            host.pruneStorage(host.installed.keys + host.pendingUnsigned.map { it.id })
        }
        // Progress covers exactly the plugins this pass will try to activate, in activation order.
        val targets = if (host.safeModeNotice == null) {
            host.installed.values.filter { host.isPluginEnabled(it.id) && covers(scope, it.id) }
        } else {
            emptyList()
        }
        var processed = 0
        host.reportProgress(targets, processed)
        host.installed.values.forEach { plugin ->
            when {
                host.safeModeNotice != null -> {
                    // The user's enabled preference is left untouched: safe mode is this process's
                    // decision, not a persistent setting. The card explains why nothing runs.
                    deactivate(plugin.id)
                    if (host.isPluginEnabled(plugin.id)) host.failures[plugin.id] = SAFE_MODE_BLOCKED
                }
                host.isPluginEnabled(plugin.id) && covers(scope, plugin.id) -> {
                    activate(plugin)
                    processed++
                    host.reportProgress(targets, processed)
                }
                else -> {
                    deactivate(plugin.id)
                    // 停用之后不该再带着"安全模式拦住它"的解释：那条状态描述的是一次加载失败，
                    // 而用户已经明确说了不要它。
                    if (host.failures[plugin.id] == SAFE_MODE_BLOCKED) host.failures.remove(plugin.id)
                }
            }
        }
        // Last pass: an enabled plugin that is neither active nor failed is the one state the card
        // used to show as a healthy row. Say it out loud instead - before this, "enabled but never
        // imported" looked exactly like "running fine" (see the agent-prompt case).
        host.installed.values.forEach { plugin ->
            if (host.isPluginEnabled(plugin.id) && plugin.id !in host.active && plugin.id !in host.failures) {
                host.failures[plugin.id] = NOT_LOADED
                android.util.Log.e("EV", "plugin ${plugin.id} enabled but not loaded")
            }
        }
    }

    /** 这个 id 是否落在此次启动范围内。 */
    fun covers(scope: LaunchScope, id: String): Boolean = when (scope) {
        LaunchScope.Full -> true
        is LaunchScope.Closure -> id in closureOf(scope.root)
    }

    /** [root] 的依赖闭包（含它自己）。环会被 [linkedSetOf] 挡住。 */
    private fun closureOf(root: String): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(id: String) {
            if (!result.add(id)) return
            host.installed[id]?.dependencies?.forEach { visit(it) }
        }
        visit(root)
        return result
    }

    suspend fun activate(plugin: InstalledPlugin) {
        if (host.active.containsKey(plugin.id)) return
        // Process-wide gate: even a caller that forgot to check safe mode cannot load a plugin.
        // `exitSafeMode` clears the mode before it activates anything, so this never blocks recovery.
        if (host.safeModeNotice != null) {
            host.failures[plugin.id] = SAFE_MODE_BLOCKED
            return
        }
        host.failures.remove(plugin.id)
        // Capability gate before any loading work: an ungranted declaration means the plugin may
        // not run, and the card says why so the user can grant it.
        val missing = PermissionGate.missing(
            plugin.requestedPermissions,
            host.permissions.granted(plugin.id),
        )
        if (missing.isNotEmpty()) {
            host.failures[plugin.id] = PermissionGate.denialMessage(plugin.id, missing)
            return
        }
        // The sync pass already hashed these bytes and the private copy is verified across the copy
        // and locked read-only afterwards, so re-reading every container here (27 MB twice over)
        // only added to startup time. A private copy that somehow changed still fails the loader's
        // own expectedSha256 check, because it is compared against this value.
        val descriptor = AndroidModuleDescriptor(
            id = plugin.id,
            version = plugin.version,
            entryClass = plugin.entryClass,
            file = plugin.file,
            expectedSha256 = plugin.sha256,
            // Without this the loader never links a plugin to its dependency loader.
            // 内核的 descriptor 只认 id：`agent.tools@1.1.0` 传下去会变成"模块未注册"。
            // 版本在装配阶段由 PluginValidator 检查过，这里只交出 id。
            dependencies = plugin.dependencies.mapNotNull {
                ev.buan.hostcore.dependency.PluginDependency.parse(it)?.id
            },
            sharedHostPackages = HostPackages.shared,
        )
        var scopeFiber: Fiber<Unit>? = null
        try {
            host.modules.register(descriptor)
            val instance = host.modules.import(host.modules.moduleUrl(plugin.id), null) as? Plugin<Unit>
                ?: error("入口 ${plugin.entryClass} 未实现 org.cordis.Plugin")
            // A declaration is recorded before the plugin can fail later: the point of the manifest
            // is to describe the container, and it stays true even if the plugin then throws.
            (instance as? PluginSettings)?.let { host.pluginSettings[plugin.id] = it }
            (instance as? org.cordis.api.ManifestProvider)?.manifest?.let { declared ->
                if (declared.id == plugin.id) host.pluginManifests[plugin.id] = declared
            }
            // One host-owned Fiber per plugin, and the provider is installed on it. Providing from
            // `cordis.extend { isolate(...) }` installs the binding on the *root* Fiber's service
            // snapshot, and the kernel resolver then hands the last provided instance to any plugin
            // that reads the key without declaring `inject` (measured in the T06 spike). A dedicated
            // Fiber keeps the provider reachable only from this plugin's descendants.
            val scope = host.cordis.plugin(scopePlugin(plugin.id), Unit)
            scopeFiber = scope
            withTimeout(START_TIMEOUT_MS) { scope.await() }
            val pluginContext = scope.ctx
                .isolate(PermissionService.Key)
                .isolate(PluginStorage.Key)
                .isolate(UserDataStorage.Key)
                .isolate(BackgroundService.Key)
                // Per-plugin too: the budget service knows whose lifetime and whose name it carries,
                // and without isolation the kernel would hand the last provided instance to every
                // plugin that reads the key (see the note above on isolated Fibers).
                .isolate(RuntimeBudgetService.Key)
            val permissionService = PermissionServiceImpl(plugin.id, host.permissions)
            pluginContext.provide(PermissionService.Key, permissionService)
            // Bound by contract, not by convention: a plugin that wants to do work asks the host
            // whether it may start and how long it has. The job is per-plugin so deactivation
            // cancels everything it still had in flight (ADR-0004 验收: 卸载后任务全部被收掉).
            val budgetLifetime = SupervisorJob()
            host.budgetJobs[plugin.id] = budgetLifetime
            pluginContext.provide(
                RuntimeBudgetService.Key,
                host.makeBudget(plugin.id, budgetLifetime),
            )
            // Storage is lent on the same isolated Context: relative paths can only resolve inside
            // this plugin's own files/plugins/<id> and cache/plugins/<id>. It is declaration-gated
            // here as well: a plugin that never declared PLUGIN_STORAGE gets no binding at all
            // (ctx[PluginStorage.Key] stays null), while the binding handed to a declarer re-checks
            // the grant on every data call.
            providePluginStorageIfDeclared(
                pluginContext,
                plugin.requestedPermissions,
                storage = { PluginStorageImpl(host.appContext, plugin.id, permissionService) },
            )
            // Credentials are the host's to keep: the plugin gets an API, never a path. A plugin
            // that never declared SECRETS gets no binding at all (ctx[CredentialService.Key] is null).
            provideCredentialIfDeclared(
                pluginContext,
                plugin.requestedPermissions,
                credentials = { host.makeCredentials(plugin.id, permissionService) },
            )
            // The user's own data (conversations, keys, memories) lives in the host's tree, not in
            // the plugin area: same gate, but nothing in the plugin lifecycle deletes it.
            provideUserDataIfDeclared(
                pluginContext,
                plugin.requestedPermissions,
                storage = { UserDataStorageImpl(host.appContext, plugin.id, permissionService) },
            )
            // Background execution is host-mediated too: the plugin takes references, the host owns
            // the process-wide foreground service and the notification the user can see.
            pluginContext.provide(
                BackgroundService.Key,
                host.makeBackgroundService(plugin.id, permissionService),
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
            host.active[plugin.id] = scope
        } catch (timeout: TimeoutCancellationException) {
            host.failures[plugin.id] = "启动超时（${START_TIMEOUT_MS / 1000} 秒）"
            host.budgetJobs.remove(plugin.id)?.cancel()
            runCatching { scopeFiber?.dispose() }
        } catch (error: Throwable) {
            // The card must show *why*, not just the wrapper type: a reflective loader failure
            // surfaces as InvocationTargetException whose cause names the missing class or method,
            // and that cause is the only actionable part of the report.
            android.util.Log.e("EV", "plugin ${plugin.id} activation failed", error)
            host.failures[plugin.id] = generateSequence(error) { it.cause }
                .map { "${it.javaClass.name}: ${it.message.orEmpty()}" }
                .joinToString("  ←  ")
            host.budgetJobs.remove(plugin.id)?.cancel()
            runCatching { scopeFiber?.dispose() }
        }
    }

    suspend fun deactivate(id: String) {
        // A stopped plugin must not keep the host's foreground service alive; its references are
        // dropped before the scope (and therefore the plugin) goes away.
        host.releaseBackground(id)
        // Budget tasks first: cancelling the lifetime also frees the slots they hold, so the gate
        // never counts work belonging to a plugin that is already gone.
        host.budgetJobs.remove(id)?.cancel()
        // The scope Fiber owns the plugin Fiber and both providers; one dispose tears down the whole
        // per-plugin subtree. Storage is deliberately left in place: disabling keeps plugin data.
        host.active.remove(id)?.let { scope -> runCatching { scope.dispose() } }
        runCatching { host.modules.release(id) }
    }

    /** Empty plugin whose only job is to own the per-plugin scope Fiber. */
    private fun scopePlugin(id: String): Plugin<Unit> = plugin(name = "host/scope/$id") { _, _ -> }

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
    }
}
