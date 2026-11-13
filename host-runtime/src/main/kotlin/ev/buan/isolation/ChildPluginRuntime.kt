package ev.buan.isolation

import android.content.Context
import ev.buan.PermissionServiceImpl
import ev.buan.PermissionStore
import ev.buan.PluginStorageImpl
import ev.buan.ServiceDirectoryImpl
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.withTimeout
import org.cordis.Context as CordisContext
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.Plugin
import org.cordis.api.HostPackages
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.loader.AndroidModuleDescriptor
import org.cordis.loader.AndroidModuleLoader
import org.cordis.plugin

/**
 * 子进程里的插件运行时：只装工具类插件，别的一概不碰。
 *
 * 与宿主的 `HostActivation` 有三处刻意的不同，每一处都是"隔离"二字的代价：
 *
 *  1. **只提供三个服务**：`PermissionService`、`ToolRegistry`、按需的 `PluginStorage`。
 *     其它宿主服务在这个进程里没有实现，插件 `inject` 它们会永远等不到提供者——
 *     所以能不能进这里由 `RestrictedProfile` 在装配阶段就先判掉。
 *  2. **不装 UI**：插件在这里也算不出 `AgentUi` / 桌面入口，那些只有宿主能画。
 *  3. **一个插件一颗 Fiber，失败只影响它自己**：这个进程里没有第二个插件需要连坐。
 */
internal class ChildPluginRuntime(private val context: Context) {

    private val root = CordisContext()
    private val modules = AndroidModuleLoader(context)
    private val running = mutableMapOf<String, Running>()

    private class Running(val scope: Fiber<Unit>, val registry: MemoryToolRegistry)

    /** 装一个插件，返回它注册的工具。失败抛异常，由 binder 层转成错误码。 */
    suspend fun load(
        id: String,
        filePath: String,
        entryClass: String,
        version: String,
        sha256: String,
        storageGranted: Boolean,
    ): List<ToolSpec> {
        unload(id)
        val registry = MemoryToolRegistry()
        modules.register(
            AndroidModuleDescriptor(
                id = id,
                version = version,
                entryClass = entryClass,
                file = java.io.File(filePath),
                expectedSha256 = sha256,
                // 依赖由装配阶段的 RestrictedProfile 挡在外面：子进程只装一个模块。
                dependencies = emptyList(),
                sharedHostPackages = HostPackages.shared,
            ),
        )
        val instance = modules.import(modules.moduleUrl(id), null) as? Plugin<Unit>
            ?: error("入口 $entryClass 未实现 org.cordis.Plugin")
        val scope = root.plugin(scopePlugin(id), Unit)
        withTimeout(SCOPE_TIMEOUT_MS) { scope.await() }
        val pluginContext = scope.ctx
            .isolate(PermissionService.Key)
            .isolate(ToolRegistry.Key)
            .isolate(PluginStorage.Key)
            .isolate(ServiceDirectory.Key)
        // 授权仍然归宿主：这里的 store 读的是宿主进程写的同一份偏好，插件改不了它。
        val permissions = PermissionServiceImpl(id, PermissionStore(context))
        pluginContext.provide(PermissionService.Key, permissions)
        pluginContext.provide(ToolRegistry.Key, registry)
        // 插件拿工具总线的方式是"从服务目录里查"，不是 `inject ToolRegistry`——
        // 目录里只有这个进程自己的那一份，所以查到的也只能是它。
        val directory = ServiceDirectoryImpl()
        directory.publish(ToolRegistry.Key, registry)
        pluginContext.provide(ServiceDirectory.Key, directory)
        if (storageGranted) {
            pluginContext.provide(PluginStorage.Key, PluginStorageImpl(context, id, permissions))
        }
        val fiber = pluginContext.plugin(instance, Unit)
        withTimeout(APPLY_TIMEOUT_MS) { fiber.await() }
        // 与宿主同一条判据：PENDING 表示 inject 从来没被满足，apply 根本没跑。
        if (fiber.state == FiberState.PENDING) {
            error("插件没有启动：它 inject 的服务在这个进程里没有提供者")
        }
        running[id] = Running(scope, registry)
        return registry.specs()
    }

    suspend fun invoke(id: String, tool: String, argumentsJson: String) =
        run {
            val owner = running[id] ?: error("插件 $id 不在子进程里")
            val target = owner.registry.lookup(tool) ?: error("插件 $id 没有工具 $tool")
            target.invokeResult(argumentsJson)
        }

    /** 把这个进程里还活着的插件全部拆掉：解绑/退出前调，否则 dex 与 Fiber 都留在原地。 */
    suspend fun unloadAll() {
        running.keys.toList().forEach { unload(it) }
    }

    suspend fun unload(id: String) {
        running.remove(id)?.let { owner -> runCatching { owner.scope.dispose() } }
        runCatching { modules.release(id) }
    }

    private fun scopePlugin(id: String): Plugin<Unit> = plugin(name = "child/scope/$id") { _, _ -> }

    private companion object {
        const val SCOPE_TIMEOUT_MS = 20_000L
        const val APPLY_TIMEOUT_MS = 30_000L
    }
}
