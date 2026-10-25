package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation brain: one [AgentService] over three optional capability plugins.
 *
 * A turn is `system? -> memory.history -> model.complete -> (tool calls) -> model.complete ->
 * memory.append`. The optional system prompt is supplied by the caller per turn: it goes ahead of
 * history and the current user message, and is never appended to memory, so a role instruction
 * cannot leak into the stored conversation. Every capability is optional and looked up per call
 * through [CapabilityProvider]:
 *  - no model              -> a readable "not connected / not configured" reply, never an exception;
 *  - no tools              -> plain text turns, tool requests are answered with a degraded result;
 *  - no memory             -> single-turn conversation;
 *  - a capability throw    -> the turn becomes an error reply instead of killing the host.
 *
 * Tool calls are executed here, not inside the model plugin: the contract makes executing them the
 * caller's job. Results go back under the native tool protocol - the assistant's own tool-call
 * message travels ahead of one [AgentRole.TOOL] message per result, ids matching - and both are
 * persisted to memory, so the model's view of a turn can always be rebuilt from stored history.
 *
 * ## One loop, two entry points
 *
 * The loop lives in [sendStreaming] and reports [AgentProgress] as it runs; [send] is a thin
 * collector of that stream. Writing the logic twice would eventually let the two drift apart, and
 * the streaming variant is the one the UI needs.
 *
 * ## Why a turn stops
 *
 * The loop is bounded by a budget, not by a fixed round count, and it always reports the reason it
 * stopped ([AgentStop]). Three separate guards, because each one failed for a different reason:
 *  - [Budget] caps rounds, tool calls, prompt size and wall clock (approval waiting excluded);
 *  - a repeated tool call with an unchanged result gets one reflection, a third try ends the turn;
 *  - two consecutive turns producing the same normalized text end the turn as `NO_PROGRESS`.
 *
 * When the budget - not the model - ends the turn, one final completion without tools asks the
 * model to summarize. The user then gets "here is what I did and what is left" instead of a
 * half-finished answer, and `stop` explains why.
 */
class AgentBrainService internal constructor(
    private val capabilities: CapabilityProvider,
    /** Injectable so tests can shrink the budget instead of scripting 60 tool calls. */
    private val budget: Budget = Budget(),
) : AgentService {

    /** True when a model plugin is published and serves at least one configured model. */
    override val ready: Boolean
        get() = try {
            capabilities.current().model?.models?.isNotEmpty() == true
        } catch (error: Throwable) {
            false
        }

    /** Collects [sendStreaming] down to its final reply. */
    override suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): AgentReply {
        var finished: AgentReply? = null
        sendStreaming(sessionId, text, model, systemPrompt).collect { progress ->
            if (progress is AgentProgress.Done) finished = progress.reply
        }
        return finished ?: degraded(model, "智能体没有返回任何结果，请稍后重试。")
    }

    override fun sendStreaming(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): Flow<AgentProgress> = flow {
        turn(sessionId, text, model, systemPrompt)
    }.flowOn(Dispatchers.IO)

    /**
     * The loop itself. Every event is emitted from this one coroutine: [flowOn] moves the whole
     * flow, it does not cross contexts per emission.
     */
    private suspend fun FlowCollector<AgentProgress>.turn(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ) {
        val current = capabilities.current()
        val modelService = current.model
        if (modelService == null) {
            emit(AgentProgress.Done(degraded(model, "未连接模型插件：请在插件管理页启用 $MODEL_PLUGIN，授予 NETWORK / PLUGIN_STORAGE 并完成配置。")))
            return
        }
        val available = modelService.models
        if (available.isEmpty()) {
            emit(AgentProgress.Done(degraded(model, "模型插件已启用但尚未配置：打开插件管理 → 模型服务，填写接口地址、API Key 与模型名后保存。")))
            return
        }
        val target = selectModel(available, model)
        if (target == null) {
            emit(AgentProgress.Done(degraded(model, "未找到模型 ${model?.model}；当前可用：${available.joinToString("、") { it.model }}。")))
            return
        }

        val tools = current.tools
        val specs: List<ToolSpec> = tools?.specs().orEmpty()
        val memory = current.memory
        val events = mutableListOf<AgentEvent>()
        val usages = mutableListOf<AgentUsage?>()

        val userMessage = AgentMessage(role = AgentRole.USER, content = text)
        // A role instruction belongs to this request only: it goes in front of history and the
        // current user message, and is never appended to memory.
        val inputs = mutableListOf<AgentMessage>()
        systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { inputs += AgentMessage(AgentRole.SYSTEM, it) }
        inputs += historyOf(memory, sessionId)
        inputs += userMessage
        remember(memory, sessionId, userMessage)

        val startedAt = System.nanoTime()
        // Time the user spends deciding is not the agent's time: a phone in a pocket must not eat
        // the wall-clock budget of the turn.
        var approvalNanos = 0L

        var rounds = 0
        var toolCalls = 0
        var lastReply: AgentReply? = null
        var previousNormalized: String? = null
        val seenResults = mutableMapOf<String, String>()
        var repeatStreak = 0
        var stop = AgentStop(AgentStop.Reason.ROUNDS, 0, 0)

        while (true) {
            val elapsedMs = (System.nanoTime() - startedAt - approvalNanos) / 1_000_000
            if (rounds >= budget.maxRounds) {
                stop = AgentStop(AgentStop.Reason.ROUNDS, rounds, toolCalls)
                break
            }
            if (elapsedMs > budget.deadlineMs) {
                stop = AgentStop(AgentStop.Reason.DEADLINE, rounds, toolCalls)
                break
            }
            if (promptChars(inputs) > budget.maxPromptChars) {
                stop = AgentStop(AgentStop.Reason.ROUNDS, rounds, toolCalls, "上下文超出预算")
                break
            }

            rounds++
            emit(AgentProgress.RoundStarted(rounds))
            val reply = try {
                modelService.complete(target, inputs, specs)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // The successful earlier rounds still consumed tokens; report what was measured.
                emit(AgentProgress.Done(failure(target, error, AgentUsageTotals.aggregate(usages))))
                return
            }
            events += reply.events
            usages += reply.usage
            lastReply = reply

            val calls = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_CALL }
            if (calls.isEmpty()) {
                stop = AgentStop(AgentStop.Reason.FINISHED, rounds, toolCalls)
                break
            }

            val normalized = normalize(reply.message.content)
            if (normalized.isNotEmpty() && normalized == previousNormalized) {
                emit(AgentProgress.RoundFinished(rounds))
                stop = AgentStop(AgentStop.Reason.NO_PROGRESS, rounds, toolCalls)
                break
            }
            previousNormalized = normalized

            // Native protocol: the assistant's tool-call turn goes back ahead of the results, so
            // the provider sees its own calls followed by the tool messages that answer them.
            val roundCalls = calls.mapIndexed { index, call -> toolCallOf(index, call) }
            val assistantTurn = reply.message.copy(toolCalls = roundCalls)
            inputs += assistantTurn
            remember(memory, sessionId, assistantTurn)

            // Every call in this turn must be answered, including the ones a guard skipped: an
            // assistant turn with unanswered tool_calls is an invalid request for the final call.
            val outcomes = mutableListOf<Outcome>()
            var stopAfterRound: AgentStop? = null
            for (call in roundCalls) {
                val toolStartedAt = System.nanoTime()
                emit(
                    AgentProgress.ToolStarted(
                        round = rounds,
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
                if (toolCalls >= budget.maxToolCalls) {
                    outcomes += Outcome.ToolResult(call, SKIPPED_RESULT, StepState.Skipped)
                    emit(AgentProgress.ToolFinished(call.id, StepState.Skipped, 0, null))
                    stopAfterRound = AgentStop(AgentStop.Reason.TOOL_BUDGET, rounds, toolCalls)
                    continue
                }
                toolCalls++
                val executed = executeGuarded(tools, call, current.approval) {
                    emit(AgentProgress.ToolAwaitingApproval(call.id))
                }
                approvalNanos += executed.approvalNanos
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
                val prior = seenResults.put(fingerprint, result)
                outcomes += Outcome.ToolResult(call, result, executed.state)
                if (prior != null && prior == result) {
                    repeatStreak++
                    if (repeatStreak >= REPEAT_LIMIT) {
                        stopAfterRound = AgentStop(
                            reason = AgentStop.Reason.REPEATED_CALLS,
                            rounds = rounds,
                            toolCalls = toolCalls,
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
                inputs += toolMessage
                remember(memory, sessionId, toolMessage)
                events += AgentEvent(
                    kind = AgentEvent.Kind.TOOL_RESULT,
                    text = outcome.result,
                    toolName = outcome.call.name,
                    toolCallId = outcome.call.id,
                )
            }
            // One nudge per round is enough: several identical reminders would just eat context.
            outcomes.filterIsInstance<Outcome.Reflection>().firstOrNull()?.let { nudge ->
                // A system message in the middle of a conversation is rejected or ignored by some
                // OpenAI-compatible servers, so the nudge rides as a user message and says
                // explicitly that it comes from the runtime.
                val message = AgentMessage(AgentRole.USER, nudge.text)
                inputs += message
                remember(memory, sessionId, message)
            }

            emit(AgentProgress.RoundFinished(rounds))
            stopAfterRound?.let {
                stop = it
                break
            }
        }

        val finished = stop.reason == AgentStop.Reason.FINISHED
        val content = if (finished) {
            lastReply?.message?.content?.takeIf { it.isNotBlank() } ?: FALLBACK_NO_TEXT
        } else {
            emit(AgentProgress.Finalizing)
            wrapUp(stop, inputs, target, modelService, usages, events)
        }
        if (content.isNotBlank()) {
            remember(memory, sessionId, AgentMessage(role = AgentRole.ASSISTANT, content = content))
        }

        emit(
            AgentProgress.Done(
                AgentReply(
                    message = AgentMessage(role = AgentRole.ASSISTANT, content = content),
                    model = target,
                    events = events,
                    usage = AgentUsageTotals.aggregate(usages),
                    stop = stop,
                ),
            ),
        )
    }

    /**
     * One last completion without tools, so a budget stop still produces a readable answer.
     *
     * Failure is not fatal: the caller gets [FALLBACK_STOPPED] plus the [AgentStop] reason instead
     * of an exception, because losing the whole turn to a failed summary would be worse than the
     * half-finished answer this exists to avoid.
     */
    private suspend fun wrapUp(
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

    /** Null when the caller asked for a model this provider does not serve. */
    private fun selectModel(available: List<ModelRef>, requested: ModelRef?): ModelRef? =
        if (requested == null) available.firstOrNull()
        else available.firstOrNull { it.model == requested.model }

    private suspend fun historyOf(memory: MemoryService?, sessionId: String): List<AgentMessage> {
        if (memory == null) return emptyList()
        return try {
            HistoryTrim.trim(memory.history(sessionId), MAX_HISTORY_TURNS, MAX_HISTORY_CHARS)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // A memory failure degrades to a stateless turn; it must not lose the user's message.
            emptyList()
        }
    }

    private suspend fun remember(memory: MemoryService?, sessionId: String, message: AgentMessage) {
        if (memory == null) return
        try {
            memory.append(sessionId, message)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Best effort: the reply is still useful even when it cannot be persisted.
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

    /**
     * Runs one requested tool and reports how long the human spent deciding.
     *
     * A missing registry or an unknown tool becomes result *text* for the next model turn instead
     * of an exception, so a model hallucinating a tool name cannot abort the conversation.
     * Dangerous tools go through the approval gate first (fail-closed when none is published);
     * the refusal is result text too, so the model can explain instead of retrying blindly.
     *
     * The approval wait sits **outside** the execution timeout: a person deciding must not be
     * "timed out", they are the reason the call is slow. The timeout only bounds the tool itself.
     */
    private suspend fun executeGuarded(
        registry: ToolRegistry?,
        call: AgentToolCall,
        approval: ApprovalService?,
        onAwaitingApproval: suspend () -> Unit,
    ): Executed {
        if (registry == null) {
            return Executed("工具系统未启用（未安装 $TOOLS_PLUGIN）", 0, StepState.Failed)
        }
        val tool = registry.lookup(call.name)
            ?: return Executed("没有名为 ${call.name} 的工具", 0, StepState.Failed)

        var approvalNanos = 0L
        if (tool.spec.dangerous) {
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

        val timeout = timeoutFor(tool.spec.name)
        var failed = false
        val result = try {
            withTimeoutOrNull(timeout) { tool.invoke(call.argumentsJson) }
                ?: "工具 ${call.name} 超时（${timeout / 1000}s），本次未完成"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            failed = true
            "工具调用失败：${error.message ?: error::class.simpleName}"
        }
        if (result.startsWith("工具 ${call.name} 超时")) failed = true
        return Executed(result, approvalNanos, if (failed) StepState.Failed else StepState.Ok)
    }

    private fun degraded(model: ModelRef?, reason: String, usage: AgentUsage? = null): AgentReply =
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

    private fun reflection(call: AgentToolCall): String =
        "（运行时提示）你刚才用相同参数再次调用了 ${call.name}，结果没有变化。" +
            "换一种方法，或直接说明为什么无法继续。"

    private fun promptChars(messages: List<AgentMessage>): Int =
        messages.sumOf { it.content.length + PER_MESSAGE_OVERHEAD_CHARS }

    /** Whitespace-free, lowercase, trailing punctuation dropped: "no progress" is about meaning. */
    private fun normalize(text: String): String =
        text.lowercase()
            .filterNot { it.isWhitespace() }
            .trimEnd('。', '！', '？', '.', '!', '?')

    private fun timeoutFor(toolName: String): Long =
        if (toolName == SHELL_TOOL_NAME) budget.shellTimeoutMs else budget.toolTimeoutMs

    /** What a tool call produced, in the order it must be fed back to the model. */
    private sealed interface Outcome {
        data class ToolResult(
            val call: AgentToolCall,
            val result: String,
            val state: StepState,
        ) : Outcome

        data class Reflection(val text: String) : Outcome
    }

    private data class Executed(val result: String, val approvalNanos: Long, val state: StepState)

    private companion object {
        const val MODEL_PLUGIN = "ev.buan.plugins.agent.model"
        const val TOOLS_PLUGIN = "ev.buan.plugins.agent.tools"

        /** Model calls one user turn may make. A guard against loops, not a task-size estimate. */
        const val MAX_ROUNDS = 20

        /** Tool invocations one user turn may make. */
        const val MAX_TOOL_CALLS = 60

        /** Wall-clock budget, excluding time the human spends on approval. */
        const val DEADLINE_MS = 300_000L

        /** Prompt size budget; the history view is bounded separately by [MAX_HISTORY_CHARS]. */
        const val MAX_PROMPT_CHARS = 200_000

        /** Counting overhead per message (role markers, separators) when budgeting a prompt. */
        const val PER_MESSAGE_OVERHEAD_CHARS = 32

        const val SHELL_TOOL_NAME = "run_shell"

        /** Second repeated call gets a reflection; the third ends the turn. */
        const val REPEAT_LIMIT = 2

        /** Summary call after a budget stop. */
        const val WRAP_TIMEOUT_MS = 60_000L

        /** ... and after a DEADLINE stop, where the budget is already spent. */
        const val WRAP_TIMEOUT_AFTER_DEADLINE_MS = 15_000L

        const val SKIPPED_RESULT = "预算用尽，本次调用未执行"

        /** Tool output is fed back into the prompt, so one file cannot swallow the context window. */
        const val MAX_TOOL_RESULT_CHARS = 8_000

        /** Display-only excerpt carried by [AgentProgress.ToolFinished]. */
        const val PREVIEW_CHARS = 300

        /**
         * Bounds of the model-facing history view (the stored log itself is never trimmed):
         * enough context for continuity, small enough that tool rounds cannot blow up the window.
         */
        const val MAX_HISTORY_TURNS = 20
        const val MAX_HISTORY_CHARS = 24_000

        const val FALLBACK_NO_TEXT = "模型没有返回文本。"
        const val FALLBACK_STOPPED =
            "本轮因预算用尽而停止，收尾总结也未能生成。已完成的操作已保存在会话历史里。"
    }
}

/**
 * How much one user turn may spend.
 *
 * These caps replaced the old three-round limit. They exist to stop a runaway loop, not to model
 * how much work a task needs: 20 rounds covers a realistic multi-file task, and the wall clock is
 * what actually protects a user from a stuck turn.
 */
internal data class Budget(
    val maxRounds: Int = 20,
    val maxToolCalls: Int = 60,
    val deadlineMs: Long = 300_000L,
    val maxPromptChars: Int = 200_000,
    /** Default per-tool execution timeout. */
    val toolTimeoutMs: Long = 30_000L,
    /**
     * Shell gets its own budget: `run_shell` bounds itself at 120s and force-kills the process, so
     * the brain must not give up earlier and report "timed out" while work still runs.
     */
    val shellTimeoutMs: Long = 120_000L,
)
