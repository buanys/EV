package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.ToolGuardRegistry
import ev.buan.plugins.agent.api.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tool half of one round: one call through the three gates, plus the helpers that turn a
 * [AgentToolCall] into something either the user or the model can read.
 *
 * Split out of [TurnRunner] so the round itself stays readable top to bottom. Nothing here reaches
 * back into the runner: what it used to read through the class - [guards] and [budget] - is a
 * parameter now, so the dependencies are visible at the call site instead of implied by `this`.
 */

/** What one guarded call produced, in the order [TurnRunner.turnOnce] feeds it back to the model. */
internal data class Executed(
    val result: String,
    val approvalNanos: Long,
    val state: StepState,
    val images: List<AgentImage> = emptyList(),
)

/**
 * 跑一次工具调用，按顺序过三道：硬规则、预判、人工审批。
 *
 * 三道的强度不同，顺序也不可换：
 *  1. [ToolGuardRegistry] —— 硬规则，任何人都绕不过，包括 policy 和用户自己批过；
 *  2. [ApprovalPolicy] —— 预判，只能把"要问人"变成"直接放行/直接拒绝"；
 *  3. [ApprovalService] —— 问人，挂起直到有人回答，没人回答就是拒绝。
 *
 * fail-closed 贯穿全程：缺 registry、缺 gate、policy 抛异常、gate 抛异常，全部拒绝。
 * 拒绝理由作为工具结果回给模型，所以这一轮会降级，而不是直接死掉。
 *
 * The approval wait sits **outside** the execution timeout: a person deciding must not be
 * "timed out", they are the reason the call is slow. The timeout only bounds the tool itself.
 *
 * [guards] 和 [budget] 从前是 runner 的字段，现在写进签名：这个函数不再需要 [TurnRunner]，
 * 读参数就知道它依赖什么（硬规则、以及超时该按哪个量级算）。
 */
internal suspend fun executeGuarded(
    registry: ToolRegistry?,
    call: AgentToolCall,
    approval: ApprovalService?,
    policy: ApprovalPolicy?,
    guards: ToolGuardRegistry,
    budget: Budget,
    onAwaitingApproval: suspend () -> Unit,
): Executed {
    if (registry == null) {
        return Executed("工具系统未启用（未安装 $TOOLS_PLUGIN）", 0, StepState.Failed)
    }
    val tool = registry.lookup(call.name)
        ?: return Executed("没有名为 ${call.name} 的工具", 0, StepState.Failed)

    // 硬规则先过：不管谁批过、是不是高危，guard 说不行就是不行。
    // 放在审批之前而不是之后，是因为"先问人再被规则否决"会让用户白做一次决定。
    val veto = try {
        guards.vet(call)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        // 坏掉的 guard 不能变成放行。
        "已拦截：安全规则插件出错（${error.message ?: error::class.simpleName}），本次未执行"
    }
    if (veto != null) return Executed(veto, 0, StepState.Failed)

    var approvalNanos = 0L
    if (tool.spec.dangerous) {
        // 预判：policy 只能把 ASK 变成 ALLOW / DENY，绕不过上面的 guard。
        // 没有 policy 时一律 ASK，行为与它出现之前完全一致。
        val verdict = try {
            policy?.judge(call.name, call.argumentsJson) ?: ApprovalPolicy.Verdict.ASK
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return Executed(
                "已拦截：审批策略出错（${error.message ?: error::class.simpleName}），本次未执行",
                0,
                StepState.Failed,
            )
        }
        when (verdict) {
            ApprovalPolicy.Verdict.DENY -> return Executed(
                "已拒绝：审批策略禁止 ${call.name} 在本次执行",
                0,
                StepState.Failed,
            )

            ApprovalPolicy.Verdict.ALLOW -> Unit // 明确安全，不问人

            ApprovalPolicy.Verdict.ASK -> {
                val gate = approval ?: return Executed(
                    "已拦截：${call.name} 属于高危操作，需要人工审批，但审批界面未启用（请启用 ev-ui 后重试）",
                    0,
                    StepState.Failed,
                )
                onAwaitingApproval()
                val startedApproval = System.nanoTime()
                val decision = try {
                    gate.request(call.name, call.argumentsJson)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    // A broken gate must never become an approval.
                    return Executed(
                        "已拦截：审批通道出错（${error.message ?: error::class.simpleName}），本次未执行",
                        System.nanoTime() - startedApproval,
                        StepState.Failed,
                    )
                }
                approvalNanos = System.nanoTime() - startedApproval
                if (decision == ApprovalDecision.DENY) {
                    return Executed("用户拒绝了本次执行", approvalNanos, StepState.Failed)
                }
            }
        }
    }

    val timeout = timeoutFor(tool.spec.name, budget)
    var failed = false
    var images: List<AgentImage> = emptyList()
    val result = try {
        val produced = withTimeoutOrNull(timeout) { tool.invokeResult(call.argumentsJson) }
        if (produced == null) {
            "工具 ${call.name} 超时（${timeout / 1000}s），本次未完成"
        } else {
            images = produced.images
            produced.text
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        failed = true
        "工具调用失败：${error.message ?: error::class.simpleName}"
    }
    if (result.startsWith("工具 ${call.name} 超时")) failed = true
    return Executed(result, approvalNanos, if (failed) StepState.Failed else StepState.Ok, images)
}

/**
 * 把人看不懂的工具名翻成一句话。
 *
 * `screen_tap` 是给开发者看的：用户要知道的是"它在点哪儿"。参数里带文字的（点按文字、
 * 启动应用）就带上，带不出的退回到一个动词——比原样显示函数名有用得多。
 */
internal fun describe(call: AgentToolCall): String {
    val arg = runCatching {
        org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
    }.getOrNull()

    fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        arg?.optString(key, "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    return when (call.name) {
        "screen_tap" -> "点按"
        "screen_tap_text" -> text("text")?.let { "点「$it」" } ?: "点按"
        "screen_input" -> "输入文字"
        "screen_scroll" -> if (text("direction") == "up") "向上翻" else "向下翻"
        "screen_read_all" -> "读完整页"
        "screen_wait" -> text("text")?.let { "等「$it」" } ?: "等界面"
        "screen_dump" -> "读屏"
        "screen_capture" -> "截图"
        "read_image" -> "看图"
        "screen_key" -> when (text("key")) {
            "back" -> "返回"
            "home" -> "回桌面"
            "recents" -> "任务列表"
            "notifications" -> "拉通知栏"
            else -> "按键"
        }
        "app_launch" -> text("package", "name", "app")?.let { "启动「$it」" } ?: "启动应用"
        "app_current" -> "看当前应用"
        "run_shell" -> "执行命令"
        "web_search" -> "搜索"
        "web_fetch" -> "打开网页"
        "remember", "recall", "read_memory" -> "翻记事本"
        else -> call.name
    }
}

/**
 * Normalizes a TOOL_CALL event into the contract shape. Events are the brain's source of
 * truth; a provider that omits the call id gets a synthesized one so the assistant message and
 * the tool result still correlate.
 */
internal fun toolCallOf(index: Int, call: AgentEvent): AgentToolCall = AgentToolCall(
    id = call.toolCallId?.takeIf { it.isNotBlank() } ?: "call_$index",
    name = call.toolName?.takeIf { it.isNotBlank() } ?: "unknown",
    argumentsJson = call.argumentsJson.orEmpty(),
)

private fun timeoutFor(toolName: String, budget: Budget): Long =
    if (toolName == SHELL_TOOL_NAME) budget.shellTimeoutMs else budget.toolTimeoutMs

// ---------------------------------------------------------------- 工具执行私有的常量

private const val TOOLS_PLUGIN = "ev.buan.plugins.agent.tools"

private const val SHELL_TOOL_NAME = "run_shell"
