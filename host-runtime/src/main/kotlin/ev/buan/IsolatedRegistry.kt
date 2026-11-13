package ev.buan

import android.content.Context
import ev.buan.isolation.RestrictedBroker
import ev.buan.plugins.agent.api.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * 子进程里的受限插件：谁在里面、提供了几个工具、什么时候死的。
 *
 * 拆成独立一类的理由和 [FailureScoreStoreImpl] 一样：这是**一本账**，不是装配知识。
 * 宿主进程只做三件事——记账、把工具挂上/撤下、把死亡翻译成一次失败计分。
 *
 * 挂工具的地方必须是**服务目录里那份** `ToolRegistry`（`agent-tools` 提供的那个），
 * 而不是宿主自己 new 一个：模型读的就是那一份，挂到别处等于没挂。
 */
internal class IsolatedRegistry(
    private val context: Context,
    /** 延迟取：注册时 `agent-tools` 可能还没起来，取的是"当时的那一份"。 */
    private val directory: () -> org.cordis.api.ServiceDirectory?,
    private val failures: MutableMap<String, String>,
    private val onFailure: (String) -> Unit,
    /** 状态变了让界面看一眼；在 binder 线程上被调用，所以实现里要自己切线程。 */
    private val publish: () -> Unit,
) {

    private val counts = ConcurrentHashMap<String, Int>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val broker = RestrictedBroker(context) { id -> died(id) }

    fun toolCount(id: String): Int = counts[id] ?: 0

    suspend fun start(plugin: InstalledPlugin, storageGranted: Boolean): Int {
        val tools = broker.load(plugin, storageGranted)
        val registry = directory()?.lookup(ToolRegistry.Key) ?: error("工具总线还没有就绪")
        registry.register(plugin.id, tools)
        counts[plugin.id] = tools.size
        // 减分**不在这里做**：成功计分归激活方（RestrictedActivator），一处记账就不会记两次。
        return tools.size
    }

    suspend fun stop(id: String) {
        if (counts.remove(id) == null) return
        runCatching { directory()?.lookup(ToolRegistry.Key)?.unregister(id) }
        broker.unload(id)
    }

    /**
     * 子进程没了。
     *
     * 必须在主线程上记账：`failures` 与 Compose 状态都不是线程安全的，而这个回调来自
     * binder 线程。工具也在这里撤下——一个已经不在的工具比一个报错的工具更危险，
     * 模型会以为它还能用。
     */
    private fun died(id: String) {
        if (counts.remove(id) == null) return
        main.post {
            runCatching { directory()?.lookup(ToolRegistry.Key)?.unregister(id) }
            failures[id] = "插件子进程已退出（工具已撤下）；下次启动会重新尝试"
            android.util.Log.w("EV", "plugin $id 的子进程退出：工具已撤下")
            onFailure(id)
            publish()
        }
    }
}
