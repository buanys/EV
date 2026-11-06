package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ToolGuard
import ev.buan.plugins.agent.api.ToolGuardRegistry
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * brain 侧的 guard 注册表：一次工具调用按注册顺序问一遍，第一条拒绝生效。
 *
 * 用 `CopyOnWriteArrayList` 而不是加锁：读（每次工具调用）远多于写（插件装卸原则上
 * 只在激活/卸载时发生），而且遍历期间有人注册不会打断当前这次询问。
 *
 * 撤销是幂等的：注册返回的 lambda 只标记自己那一项失效并移除，重复调用不会顺带
 * 摘掉别人后注册的 guard。
 */
class ToolGuards : ToolGuardRegistry {

    private val entries = CopyOnWriteArrayList<Entry>()

    private class Entry(val guard: ToolGuard) {
        @Volatile
        var active: Boolean = true
    }

    override fun register(guard: ToolGuard): () -> Unit {
        val entry = Entry(guard)
        entries += entry
        return {
            if (entry.active) {
                entry.active = false
                entries.remove(entry)
            }
        }
    }

    /**
     * 依次询问。某个 guard 抛异常按拒绝处理——一个坏掉的插件不能变成放行。
     *
     * 取消要原样抛出：用户按了停止，不该被当成"某条规则拒绝了这次调用"。
     */
    override suspend fun vet(call: AgentToolCall): String? {
        for (entry in entries) {
            if (!entry.active) continue
            val rejection = try {
                entry.guard.beforeTool(call)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                "已拦截：安全规则插件出错（${error.message ?: error::class.simpleName}）"
            }
            if (rejection != null) return rejection
        }
        return null
    }
}
