package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 预算用尽后的收尾：再要一次不带工具的补全，让那一轮仍然产出一段能读的回答。
 *
 * 从 `AgentBrainService` 拆出来是因为它是这条链上**唯一不碰服务状态**的一段：
 * 传什么进来就用什么，失败了也不抛——预算已经用光了，再让"总结失败"掀翻整轮，
 * 等于把半截答案也一起扔掉。
 */

/** 预算停止后的总结调用上限。 */
private const val WRAP_TIMEOUT_MS = 60_000L

/** DEADLINE 停止之后：预算已经花光，多等的每一秒都在加重超支。 */
private const val WRAP_TIMEOUT_AFTER_DEADLINE_MS = 15_000L

private const val FALLBACK_STOPPED =
    "本轮因预算用尽而停止，收尾总结也未能生成。已完成的操作已保存在会话历史里。"

/**
 * One last completion without tools, so a budget stop still produces a readable answer.
 *
 * Failure is not fatal: the caller gets [FALLBACK_STOPPED] plus the [AgentStop] reason instead
 * of an exception, because losing the whole turn to a failed summary would be worse than the
 * half-finished answer this exists to avoid.
 */
internal suspend fun wrapUp(
    stop: AgentStop,
    inputs: List<AgentMessage>,
    target: ModelRef,
    modelService: ModelService,
    usages: MutableList<AgentUsage?>,
    events: MutableList<AgentEvent>,
): String {
    if (stop.reason == AgentStop.Reason.ERROR) return FALLBACK_STOPPED
    val instruction = AgentMessage(
        role = AgentRole.USER,
        content = "（运行时提示）本轮预算已用尽：${stop.rounds} 轮、${stop.toolCalls} 次工具调用。" +
            "请只总结当前进展、已完成的部分和下一步建议，不要再调用工具。",
    )
    // After DEADLINE the budget is already gone; waiting the full wrap timeout would make the
    // overrun much worse than a short summary is worth.
    val timeout = if (stop.reason == AgentStop.Reason.DEADLINE) {
        WRAP_TIMEOUT_AFTER_DEADLINE_MS
    } else {
        WRAP_TIMEOUT_MS
    }
    val reply = withTimeoutOrNull(timeout) {
        runCatching { modelService.complete(target, inputs + instruction, emptyList()) }
            .getOrNull()
    }
    if (reply != null) {
        reply.usage?.let { usages += it }
        events += reply.events
    }
    return reply?.message?.content?.takeIf { it.isNotBlank() } ?: FALLBACK_STOPPED
}

/**
 * 用户按了停止。单独一个类型，好和真正的异常区分开——它不是失败，是本轮正常的终点。
 */
internal class AgentStopped : RuntimeException("用户停止了这一轮")
