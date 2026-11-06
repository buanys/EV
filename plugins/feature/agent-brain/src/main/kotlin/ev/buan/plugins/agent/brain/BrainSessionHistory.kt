package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import kotlinx.coroutines.CancellationException

/**
 * 会话的读写：一轮对话从哪儿开始、把消息记到哪儿去。
 *
 * 这两件事从 `AgentBrainService` 拆出来，因为它们**不依赖这个服务**——签名里
 * 要什么就传什么，没有 `this`。原来它们挂在类上纯粹因为写起来顺手，
 * 结果想验证"日志与 memory 的先后顺序"得先造一个完整的 brain。
 */

/**
 * 面向模型的历史视图（**存储的日志本身从不裁剪**）：够保持连续性，又小到工具轮次
 * 炸不掉窗口。
 */
private const val MAX_HISTORY_TURNS = 20
private const val MAX_HISTORY_CHARS = 24_000

/**
 * 历史：事件流优先，memory 兜底。
 *
 * 为什么不是"两边合并"：合并就得定义去重与顺序，而这两份数据本来就该是同一串消息。
 * 日志非空说明它记录了完整的一轮（brain 每次 remember 都先写它），直接采用；
 * 日志为空说明这是启用日志之前的会话——那时只有 memory 有内容，读它就对了。
 * 这样升级到事件流不需要迁移脚本，老会话不会突然"没有历史"。
 */
internal suspend fun historyOf(
    memory: MemoryService?,
    sessionLog: SessionLog?,
    sessionId: String,
): List<AgentMessage> {
    val stored = try {
        sessionLog?.messages(sessionId)?.takeIf { it.isNotEmpty() }
            ?: memory?.history(sessionId).orEmpty()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        // A broken store degrades to a stateless turn; it must not lose the user's message.
        emptyList()
    }
    // 被 kill 在工具执行中时，历史会停在 assistant(tool_calls) 上；
    // 不补一条合成结果，下一轮请求会被服务端以 400 永久拒绝。
    return HistoryRepair.closeDanglingToolCalls(
        HistoryTrim.trim(stored, MAX_HISTORY_TURNS, MAX_HISTORY_CHARS),
    )
}

/**
 * 记一条消息：事件流是真相源，memory 是派生物。
 *
 * 顺序不能反过来。先写 memory 再写日志，一旦日志那步失败，下一次 historyOf 会发现
 * 日志非空却断了一截，于是采用一份不完整的历史——用户会看到对话"丢了一段"。
 * 先写日志则最坏情况是 memory 少一条（蒸馏的输入旧一点），对话本身不受影响。
 */
internal suspend fun recordMessage(
    memory: MemoryService?,
    sessionLog: SessionLog?,
    sessionId: String,
    message: AgentMessage,
) {
    if (sessionLog != null) {
        try {
            sessionLog.append(sessionId, SessionEvent.Message(message))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // 日志写失败不该让这一轮死掉：memory 仍会写下同一条，最坏情况是这次不可回放。
        }
    }
    if (memory == null) return
    try {
        memory.append(sessionId, message)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        // Best effort: the reply is still useful even when it cannot be persisted.
    }
}
