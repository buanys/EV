package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelRef

/**
 * The words one round spends: the nudge that tells the model to stop repeating itself, the
 * normalisation that decides whether a round made progress, and the reply a failed turn gets
 * instead of an exception.
 *
 * Pure functions - no state, no host, no I/O. Moving them out of [TurnRunner] changes no ordering
 * and no timing, only where the reader finds them.
 */

/** 第二次相同调用得到的一句纠偏提示：不是报错，是指路。 */
internal fun reflection(call: AgentToolCall): String =
    "（运行时提示）你刚才用相同参数再次调用了 ${call.name}，结果没有变化。" +
        "换一种方法，或直接说明为什么无法继续。"

/** Whitespace-free, lowercase, trailing punctuation dropped: "no progress" is about meaning. */
internal fun normalize(text: String): String =
    text.lowercase()
        .filterNot { it.isWhitespace() }
        .trimEnd('。', '！', '？', '.', '!', '?')

/**
 * 一条"这一轮没法继续"的回复：给用户一句能读的话，给调用方一个 ERROR 停止原因。
 *
 * 降级而不是抛异常，是这个模块的约定：缺插件、缺配置都是可预期的，用户看到的应该是
 * 一句说明，而不是一次崩溃。
 */
internal fun degraded(model: ModelRef?, reason: String, usage: AgentUsage? = null): AgentReply =
    AgentReply(
        message = AgentMessage(role = AgentRole.ASSISTANT, content = reason),
        model = model,
        events = listOf(AgentEvent(kind = AgentEvent.Kind.ERROR, text = reason)),
        usage = usage,
        stop = AgentStop(AgentStop.Reason.ERROR, 0, 0, reason),
    )

/** [degraded] 的一行封装：把模型调用的异常翻成"模型调用失败：…"。 */
internal fun failure(model: ModelRef, error: Throwable, usage: AgentUsage?): AgentReply = degraded(
    model,
    "模型调用失败：${error.message ?: error::class.simpleName}",
    usage,
)
