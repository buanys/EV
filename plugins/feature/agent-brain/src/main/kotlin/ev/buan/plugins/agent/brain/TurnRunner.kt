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
import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.ToolGuardRegistry
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
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
 * arrives through [Host]; the round itself reads top to bottom in [turnOnce]. What [turnOnce] asks
 * for but does not narrate lives beside it: [executeGuarded] and [describe] in ToolExecution.kt,
 * the wording in TurnText.kt, the request prompt in TurnPrompt.kt. The runner is created once per
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
                guards = guards,
                budget = budget,
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
}

// ---------------------------------------------------------------- 循环体私有的常量

/** Second repeated call gets a reflection; the third ends the turn. */
private const val REPEAT_LIMIT = 2

private const val SKIPPED_RESULT = "预算用尽，本次调用未执行"

/** Tool output is fed back into the prompt, so one file cannot swallow the context window. */
private const val MAX_TOOL_RESULT_CHARS = 8_000

/** Display-only excerpt carried by [AgentProgress.ToolFinished]. */
private const val PREVIEW_CHARS = 300
