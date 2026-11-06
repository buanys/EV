package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.PromptContext
import ev.buan.plugins.agent.api.PromptLayer
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolGuardRegistry
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.HudPhase
import org.cordis.api.HudState

/** What one [TurnRunner.turnOnce] wants [AgentBrainService.turn] to do next. */
internal sealed interface TurnStep {
    /** The round went through: inputs are appended, run another one. */
    data object Continue : TurnStep

    /** A termination condition was hit; [stop] says which one and what it counted. */
    data class Stop(val stop: AgentStop) : TurnStep

    /**
     * The model call failed. The error [AgentReply] is already emitted, so the turn must end
     * right here - no summary completion, no TurnEnded event.
     */
    data object Abort : TurnStep
}

/**
 * The mutable state one turn carries across its rounds.
 *
 * A holder instead of a dozen parameters because most of these are *written back* by
 * [TurnRunner.turnOnce] - rounds, tool count, approval time, the repeat streak. Kotlin passes
 * Int\/Long by value, so as parameters they would each need a reference wrapper; one object is
 * honest about what they are. Its lifetime is exactly one [AgentBrainService.turn]: it is created
 * there and never stored anywhere, so no two sessions can share one.
 */
internal class TurnState(
    /** Where history is read from and messages are appended; null degrades to single-turn. */
    val memory: MemoryService?,
    /** The event log, the source of truth history is rebuilt from. */
    val sessionLog: SessionLog?,
    val sessionId: String,
    /** The tool registry, and the specs sent with every request. */
    val tools: ToolRegistry?,
    val specs: List<ToolSpec>,
    /** The approval gate and the policy that can pre-answer it. */
    val approval: ApprovalService?,
    val policy: ApprovalPolicy?,
) {
    /** The full request: system message?, stored history, user message, this turn's rounds. */
    val inputs = mutableListOf<AgentMessage>()

    /** Everything this turn produced, handed back as [AgentReply.events] verbatim. */
    val events = mutableListOf<AgentEvent>()

    /** One usage per model call, aggregated at the end. */
    val usages = mutableListOf<AgentUsage?>()

    /** Tool fingerprint -> the result it produced last time; drives the repeat guard. */
    val seenResults = mutableMapOf<String, String>()

    /** Rounds started so far. Read by the budget, by events, and by every [AgentStop]. */
    var rounds = 0

    /** Tools actually invoked so far; skipped calls do not count. */
    var toolCalls = 0

    /**
     * Wall clock spent waiting for a human, subtracted from the turn's deadline: time the user
     * spends deciding is not the agent's time, so a phone in a pocket must not eat the budget.
     */
    var approvalNanos = 0L

    /** The last answer seen, so a FINISHED turn can return it without a second call. */
    var lastReply: AgentReply? = null

    /** Normalized text of the previous tool-calling round, for the no-progress guard. */
    var previousNormalized: String? = null

    /** Consecutive identical call + identical result pairs within this turn. */
    var repeatStreak = 0
}

/**
 * The body of one agent round, extracted from [AgentBrainService].
 *
 * Everything that needs the host - pause\/stop checkpoints, message persistence, the HUD capsule -
 * arrives through [Host]; everything else (budget, tool guards, and every pure helper) lives here,
 * so the round logic can be read top to bottom in one file. The runner is created once per
 * service; all per-turn variation travels through [TurnState] and the extension receiver.
 */
internal class TurnRunner(
    /** The turn budget the service was built with; read by the tool budget and the timeouts. */
    private val budget: Budget,
    /** Hard rules checked before approval. Owned by the host, passed through unchanged. */
    private val guards: ToolGuardRegistry,
    /** The only operations that genuinely belong to [AgentBrainService]. */
    private val host: Host,
) {
    /**
     * What the round needs from the host, and nothing else. Each member names the host state it
     * touches, so nothing here can silently reach into the service:
     *  - [checkpoint] reads\/clears the pause and stop flags (and may suspend inside a pause);
     *  - [remember] writes the assistant\/tool messages to the session log and memory - the
     *    log-first ordering invariant lives in the service and is not duplicated here;
     *  - [publishHud] writes the capsule state; [currentHud] reads the last one, because the
     *    "step failed" update must build on the state the user currently sees.
     */
    internal interface Host {
        /** 每个断点都过一遍：先响应停止，再处理暂停。 */
        suspend fun checkpoint()

        /**
         * 记一条消息：事件流是真相源，memory 是派生物。写顺序（先日志后 memory）
         * 由宿主保证，runner 只负责在正确的时机调用。
         */
        suspend fun remember(
            memory: MemoryService?,
            sessionLog: SessionLog?,
            sessionId: String,
            message: AgentMessage,
        )

        /** 推送胶囊状态；宿主负责记住它，供下次在此基础上修改。 */
        fun publishHud(state: HudState)

        /** 最近一次推送的胶囊状态：失败标注要在用户当前看到的画面上改。 */
        fun currentHud(): HudState
    }

    /** What a tool call produced, in the order it must be fed back to the model. */
    private sealed interface Outcome {
        data class ToolResult(
            val call: AgentToolCall,
            val result: String,
            val state: StepState,
            val images: List<AgentImage> = emptyList(),
        ) : Outcome

        data class Reflection(val text: String) : Outcome
    }

    private data class Executed(
        val result: String,
        val approvalNanos: Long,
        val state: StepState,
        val images: List<AgentImage> = emptyList(),
    )

    /**
     * One model interaction: pause check, request, run this round's tool batch, feed the results
     * back into [TurnState.inputs].
     *
     * It answers "what happened this round" and nothing else - it does not decide whether the turn
     * goes on ([AgentBrainService.turn] owns every termination check) and it does not write the
     * final reply. Progress events are emitted here anyway, because in time they belong to this
     * interaction: RoundStarted before the request, ToolStarted\/ToolFinished per call,
     * RoundFinished once the results are in.
     */
    internal suspend fun FlowCollector<AgentProgress>.turnOnce(
        state: TurnState,
        modelService: ModelService,
        target: ModelRef,
    ): TurnStep {
        // 每轮开头是第一个断点：暂停中的 agent 不该再向模型发起一次调用。
        host.checkpoint()
        state.rounds++
        emit(AgentProgress.RoundStarted(state.rounds))
        // 等模型写答案这段时间也要有声：胶囊不能只在调工具时亮，
        // 否则"它到底在不在动"这个问题在最长的一段等待里反而没有答案。
        host.publishHud(
            HudState(
                step = state.toolCalls,
                status = if (state.toolCalls == 0) "思考中" else "整理结果",
                phase = HudPhase.RUNNING,
            ),
        )
        val reply = try {
            // Streaming is the default path: deltas reach the UI while the model is still
            // writing, and a provider that cannot stream degrades to a single delta through
            // the contract's default implementation.
            modelService.completeStreaming(target, state.inputs, state.specs) { delta ->
                emit(AgentProgress.TextDelta(state.rounds, delta))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // The successful earlier rounds still consumed tokens; report what was measured.
            emit(AgentProgress.Done(failure(target, error, AgentUsageTotals.aggregate(state.usages))))
            return TurnStep.Abort
        }
        state.events += reply.events
        state.usages += reply.usage
        state.lastReply = reply

        val calls = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_CALL }
        if (calls.isEmpty()) {
            return TurnStep.Stop(AgentStop(AgentStop.Reason.FINISHED, state.rounds, state.toolCalls))
        }

        val normalized = normalize(reply.message.content)
        if (normalized.isNotEmpty() && normalized == state.previousNormalized) {
            emit(AgentProgress.RoundFinished(state.rounds))
            return TurnStep.Stop(AgentStop(AgentStop.Reason.NO_PROGRESS, state.rounds, state.toolCalls))
        }
        state.previousNormalized = normalized

        // Native protocol: the assistant's tool-call turn goes back ahead of the results, so
        // the provider sees its own calls followed by the tool messages that answer them.
        val roundCalls = calls.mapIndexed { index, call -> toolCallOf(index, call) }
        val assistantTurn = reply.message.copy(toolCalls = roundCalls)
        state.inputs += assistantTurn
        host.remember(state.memory, state.sessionLog, state.sessionId, assistantTurn)

        // Every call in this turn must be answered, including the ones a guard skipped: an
        // assistant turn with unanswered tool_calls is an invalid request for the final call.
        val outcomes = mutableListOf<Outcome>()
        var stopAfterRound: AgentStop? = null
        for (call in roundCalls) {
            val toolStartedAt = System.nanoTime()
            emit(
                AgentProgress.ToolStarted(
                    round = state.rounds,
                    id = call.id,
                    name = call.name,
                    argsJson = call.argumentsJson,
                    startedAt = toolStartedAt,
                ),
            )
            if (stopAfterRound != null) {
                outcomes += Outcome.ToolResult(call, SKIPPED_RESULT, StepState.Skipped)
                emit(AgentProgress.ToolFinished(call.id, StepState.Skipped, 0, null))
                continue
            }
            if (state.toolCalls >= budget.maxToolCalls) {
                outcomes += Outcome.ToolResult(call, SKIPPED_RESULT, StepState.Skipped)
                emit(AgentProgress.ToolFinished(call.id, StepState.Skipped, 0, null))
                stopAfterRound = AgentStop(AgentStop.Reason.TOOL_BUDGET, state.rounds, state.toolCalls)
                continue
            }
            state.toolCalls++
            // 第二个断点：两个工具之间。工具执行中不硬停——点了一半的手指收不回来，
            // 停在动作之间才是干净的。
            host.checkpoint()
            // 屏幕上的进度：用户看不见审批弹窗了，得在胶囊上看见"现在在跑哪个工具"。
            // 走 publishHud 而不是直接 show：暂停/停止时要基于最近一次状态改，
            // 直接 show 会让 lastHud 停在旧值上。
            host.publishHud(
                HudState(
                    step = state.toolCalls,
                    tool = call.name,
                    note = describe(call),
                    status = if (call.name.startsWith("screen_")) "操作中" else "执行中",
                    phase = HudPhase.RUNNING,
                ),
            )
            val executed = executeGuarded(
                registry = state.tools,
                call = call,
                approval = state.approval,
                policy = state.policy,
            ) {
                emit(AgentProgress.ToolAwaitingApproval(call.id))
            }
            state.approvalNanos += executed.approvalNanos
            // 失败要在胶囊上留痕：否则用户只看到进度停在原地，不知道是卡住了还是做完了。
            if (executed.state == StepState.Failed) {
                host.publishHud(host.currentHud().copy(status = "这一步失败了", note = null))
            }
            val elapsedToolNanos = System.nanoTime() - toolStartedAt - executed.approvalNanos
            val result = truncateUtf16Safe(executed.result, MAX_TOOL_RESULT_CHARS)
            emit(
                AgentProgress.ToolFinished(
                    id = call.id,
                    state = executed.state,
                    elapsedMs = elapsedToolNanos / 1_000_000,
                    preview = result.take(PREVIEW_CHARS),
                ),
            )
            val fingerprint = call.name + '\u0000' + call.argumentsJson
            val prior = state.seenResults.put(fingerprint, result)
            outcomes += Outcome.ToolResult(call, result, executed.state, executed.images)
            if (prior != null && prior == result) {
                state.repeatStreak++
                if (state.repeatStreak >= REPEAT_LIMIT) {
                    stopAfterRound = AgentStop(
                        reason = AgentStop.Reason.REPEATED_CALLS,
                        rounds = state.rounds,
                        toolCalls = state.toolCalls,
                        detail = call.name,
                    )
                } else {
                    outcomes += Outcome.Reflection(reflection(call))
                }
            }
        }

        // Order is load-bearing: every TOOL message answering this assistant turn must come
        // first and contiguously, then at most one nudge. A user message between tool results
        // makes the conversation invalid for strict providers:
        //   "An assistant message with 'tool_calls' must be followed by tool messages
        //    responding to each 'tool_call_id' (insufficient tool messages)".
        for (outcome in outcomes.filterIsInstance<Outcome.ToolResult>()) {
            val toolMessage = AgentMessage(
                role = AgentRole.TOOL,
                content = outcome.result,
                name = outcome.call.name,
                toolCallId = outcome.call.id,
            )
            state.inputs += toolMessage
            host.remember(state.memory, state.sessionLog, state.sessionId, toolMessage)
            state.events += AgentEvent(
                kind = AgentEvent.Kind.TOOL_RESULT,
                text = outcome.result,
                toolName = outcome.call.name,
                toolCallId = outcome.call.id,
            )
        }
        // Images travel after every tool message and before any nudge: a picture read by a
        // tool belongs to this tool round, and strict servers reject a user message inserted
        // between tool results. Only the caption is stored - the base64 never reaches disk.
        val producedImages = outcomes.filterIsInstance<Outcome.ToolResult>()
            .flatMap { it.images }
        if (producedImages.isNotEmpty()) {
            val imageMessage = AgentMessage(
                role = AgentRole.USER,
                content = "以下是工具读取的 ${producedImages.size} 张图片，请直接查看。",
                images = producedImages,
            )
            state.inputs += imageMessage
            host.remember(state.memory, state.sessionLog, state.sessionId, imageMessage)
        }
        // One nudge per round is enough: several identical reminders would just eat context.
        outcomes.filterIsInstance<Outcome.Reflection>().firstOrNull()?.let { nudge ->
            // A system message in the middle of a conversation is rejected or ignored by some
            // OpenAI-compatible servers, so the nudge rides as a user message and says
            // explicitly that it comes from the runtime.
            val message = AgentMessage(AgentRole.USER, nudge.text)
            state.inputs += message
            host.remember(state.memory, state.sessionLog, state.sessionId, message)
        }

        emit(AgentProgress.RoundFinished(state.rounds))
        // The tool budget or the repeat guard may have fired mid-round; the break itself belongs
        // to [AgentBrainService.turn], so report the reason instead of returning early.
        val pending = stopAfterRound
        return if (pending != null) TurnStep.Stop(pending) else TurnStep.Continue
    }

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
     */
    private suspend fun executeGuarded(
        registry: ToolRegistry?,
        call: AgentToolCall,
        approval: ApprovalService?,
        policy: ApprovalPolicy?,
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

        val timeout = timeoutFor(tool.spec.name)
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
    private fun describe(call: AgentToolCall): String {
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
    private fun toolCallOf(index: Int, call: AgentEvent): AgentToolCall = AgentToolCall(
        id = call.toolCallId?.takeIf { it.isNotBlank() } ?: "call_$index",
        name = call.toolName?.takeIf { it.isNotBlank() } ?: "unknown",
        argumentsJson = call.argumentsJson.orEmpty(),
    )

    private fun reflection(call: AgentToolCall): String =
        "（运行时提示）你刚才用相同参数再次调用了 ${call.name}，结果没有变化。" +
            "换一种方法，或直接说明为什么无法继续。"

    /** Whitespace-free, lowercase, trailing punctuation dropped: "no progress" is about meaning. */
    private fun normalize(text: String): String =
        text.lowercase()
            .filterNot { it.isWhitespace() }
            .trimEnd('。', '！', '？', '.', '!', '?')

    private fun timeoutFor(toolName: String): Long =
        if (toolName == SHELL_TOOL_NAME) budget.shellTimeoutMs else budget.toolTimeoutMs
}

/** Null when the caller asked for a model this provider does not serve. */
internal fun selectModel(available: List<ModelRef>, requested: ModelRef?): ModelRef? =
    if (requested == null) available.firstOrNull()
    else available.firstOrNull { it.model == requested.model }

/**
 * The two halves of one request's prompt: the stable system message, and the volatile tail.
 *
 * [volatile] is null when no layer asked for it, which is the case for the built-in baseline -
 * a request whose tail never changes must not grow a "runtime info" section for nothing.
 */
internal data class ComposedPrompt(val system: String?, val volatile: String?)

/**
 * The prompt for one turn: the prompt plugin's layers, plus the caller's character.
 *
 * Three degradations, all deliberate:
 * - no plugin, an empty list, or a plugin that throws -> [BUILTIN_LAYERS], because a turn
 *   without a baseline is how the agent ends up giving advice instead of acting;
 * - a blank character prompt contributes nothing, it does not remove the baseline;
 * - layers that do not fit [MAX_SYSTEM_PROMPT_CHARS] are dropped whole, smallest priority
 *   first, because half a rule is worse than no rule.
 */
internal fun composePrompt(
    provider: SystemPromptService?,
    characterPrompt: String?,
    sessionId: String,
): ComposedPrompt {
    val fromPlugin = try {
        provider?.layers(PromptContext(sessionId = sessionId, nowMillis = System.currentTimeMillis()))
    } catch (error: Throwable) {
        // A broken prompt plugin must not take the turn down; the baseline still applies.
        null
    }
    val layers = if (fromPlugin.isNullOrEmpty()) BUILTIN_LAYERS else fromPlugin
    val stable = layers.filterNot { it.volatile }.toMutableList()
    characterPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
        stable += PromptLayer(PERSONA_ID, "角色", it, PERSONA_PRIORITY)
    }
    val volatile = layers.filter { it.volatile }
        .joinToString("\n") { it.body.trim() }
        .takeIf { it.isNotEmpty() }
        ?.let { "$VOLATILE_HEADER\n$it" }
    return ComposedPrompt(system = assemble(stable), volatile = volatile)
}

/** Priority descending, id ascending for ties, so the same layers always render the same way. */
private fun assemble(layers: List<PromptLayer>): String? {
    val ordered = layers.sortedWith(
        compareByDescending<PromptLayer> { it.priority }.thenBy { it.id },
    )
    val kept = mutableListOf<String>()
    var chars = 0
    for (layer in ordered) {
        val body = layer.body.trim()
        if (body.isEmpty()) continue
        val cost = body.length + PROMPT_LAYER_OVERHEAD_CHARS
        // The first layer is kept unconditionally: a prompt trimmed to nothing is worse than
        // one that overspends by a few hundred characters.
        if (kept.isNotEmpty() && chars + cost > MAX_SYSTEM_PROMPT_CHARS) continue
        kept += body
        chars += cost
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

internal fun promptChars(messages: List<AgentMessage>): Int =
    messages.sumOf { it.content.length + PER_MESSAGE_OVERHEAD_CHARS }

internal fun degraded(model: ModelRef?, reason: String, usage: AgentUsage? = null): AgentReply =
    AgentReply(
        message = AgentMessage(role = AgentRole.ASSISTANT, content = reason),
        model = model,
        events = listOf(AgentEvent(kind = AgentEvent.Kind.ERROR, text = reason)),
        usage = usage,
        stop = AgentStop(AgentStop.Reason.ERROR, 0, 0, reason),
    )

private fun failure(model: ModelRef, error: Throwable, usage: AgentUsage?): AgentReply = degraded(
    model,
    "模型调用失败：${error.message ?: error::class.simpleName}",
    usage,
)

// ---------------------------------------------------------------- 循环体私有的常量

private const val TOOLS_PLUGIN = "ev.buan.plugins.agent.tools"

/** Model calls one user turn may make. A guard against loops, not a task-size estimate. */
private const val MAX_ROUNDS = 20

/** Tool invocations one user turn may make. */
private const val MAX_TOOL_CALLS = 60

/** Wall-clock budget, excluding time the human spends on approval. */
private const val DEADLINE_MS = 300_000L

/** Counting overhead per message (role markers, separators) when budgeting a prompt. */
private const val PER_MESSAGE_OVERHEAD_CHARS = 32

private const val SHELL_TOOL_NAME = "run_shell"

/** Second repeated call gets a reflection; the third ends the turn. */
private const val REPEAT_LIMIT = 2

private const val SKIPPED_RESULT = "预算用尽，本次调用未执行"

/** Tool output is fed back into the prompt, so one file cannot swallow the context window. */
private const val MAX_TOOL_RESULT_CHARS = 8_000

/** Display-only excerpt carried by [AgentProgress.ToolFinished]. */
private const val PREVIEW_CHARS = 300

/** Room for the whole system message; see [composeSystemPrompt]. */
private const val MAX_SYSTEM_PROMPT_CHARS = 8_000

/** Counting a heading separator per layer when budgeting the system message. */
private const val PROMPT_LAYER_OVERHEAD_CHARS = 2

private const val PERSONA_ID = "persona"

/**
 * Header on the volatile tail, so the model can tell it apart from what the user typed.
 *
 * The tail rides inside the user message (a second system message mid-conversation is
 * rejected by some OpenAI-compatible servers), which makes this line the only thing
 * separating "the user said" from "the runtime appended".
 */
private const val VOLATILE_HEADER = "（运行时信息，不是用户输入）"

/** Between `style` (75) and `env` (40): a persona shapes tone, not behaviour. */
private const val PERSONA_PRIORITY = 60

/**
 * What the agent is told when no prompt plugin is installed.
 *
 * Short on purpose: it only has to stop the worst failure - an agent that talks about the
 * device instead of operating it, and one that never says when it is done. Everything
 * richer belongs to `agent-prompt`, which the user can replace.
 */
private val BUILTIN_LAYERS: List<PromptLayer> = listOf(
    PromptLayer(
        id = "identity",
        title = "身份与位置",
        priority = 100,
        body = "你运行在一台 Android 手机上的 agent 宿主里，可以用工具真的操作它。" +
            "说\"已完成\"之前必须真的调用过工具并看到结果；还没动手就说明打算怎么做。",
    ),
    PromptLayer(
        id = "stop",
        title = "什么时候停",
        priority = 90,
        body = "一轮对话有预算：最多 $MAX_ROUNDS 次模型往返、$MAX_TOOL_CALLS 次工具调用、" +
            "${DEADLINE_MS / 60_000} 分钟。做完就停；做不到要说清卡在哪，不要含糊收尾。",
    ),
)
