package ev.buan

import ev.buan.hostcore.trust.RestrictedProfile
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 受限档的执行分流：能塞进子进程就塞，塞不进去再按包的可信度决定要不要同进程跑。
 *
 * 从 [HostActivation] 拆出来，因为它是**信任判定的后半段**：激活那一段回答"谁能装起来"，
 * 这一小段回答"受限的那一档装到哪里"。合成一个方法后，`when` 里只剩一行调用。
 */
internal class RestrictedActivator(private val host: ActivationHost) {

    /**
     * 处理一个受限插件，返回 true 表示调用方应当停止激活它（已隔离，或已记录拒绝原因）。
     *
     * 顺序是刻意的：**先看能不能隔离，再看包是不是调试包**。更安全的执行环境没有理由不用，
     * 哪怕调试包允许降级。
     */
    suspend fun activate(plugin: InstalledPlugin): Boolean {
        val reason = RestrictedProfile.unsupportedReason(
            declared = plugin.separateProcess,
            permissions = plugin.requestedPermissions.mapTo(LinkedHashSet()) { it.name },
            shortcutCount = plugin.shortcuts.size,
            dependencies = plugin.dependencies,
        )
        val trust = plugin.trust
        if (reason == null) {
            isolate(plugin)
            return true
        }
        if (host.allowsRestrictedInProcess) {
            // 调试包：整包本来就可调、可接管，硬撑着"隔离"只会让开发停摆。
            // 卡片上的档位仍然是受限——放行不等于洗白。
            return false
        }
        host.failures[plugin.id] = "受限（${trust.reason}）：$reason；本包不执行受限插件"
        return true
    }

    /** 装进子进程；失败就是卡片上的一行原因 + 一次失败计分。 */
    private suspend fun isolate(plugin: InstalledPlugin) {
        try {
            val tools = host.isolate(plugin)
            host.recordFailure(plugin.id, success = true)
            tracePlugin("isolated ${plugin.id} · $tools 个工具在子进程")
        } catch (timeout: TimeoutCancellationException) {
            giveUp(plugin, "受限档隔离超时（子进程没有响应）")
        } catch (error: Throwable) {
            // 原因里必须带子进程自己那句话：binder 那头已经把异常链拼好了。
            giveUp(plugin, "受限档隔离失败：${error.message ?: error.javaClass.simpleName}")
            android.util.Log.e("EV", "plugin ${plugin.id} isolation failed", error)
        }
    }

    private suspend fun giveUp(plugin: InstalledPlugin, reason: String) {
        host.stopIsolated(plugin.id)
        host.recordFailure(plugin.id, success = false)
        host.failures[plugin.id] = reason
    }
}
