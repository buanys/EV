package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
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
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolGuard
import ev.buan.plugins.agent.api.ToolGuardRegistry
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation brain: one [AgentService] over four optional capability plugins.
 *
 * A turn is `system? -> memory.history -> model.complete -> (tool calls) -> model.complete ->
 * memory.append`. The system message is assembled per turn from the `agent/system-prompt` plugin's
 * layers plus the caller's character prompt (see [composeSystemPrompt]): it goes ahead of history
 * and the current user message, and is never appended to memory, so a role instruction cannot leak
 * into the stored conversation. Every capability is optional and looked up per call through
 * [CapabilityProvider]:
 *  - no model              -> a readable "not connected / not configured" reply, never an exception;
 *  - no tools              -> plain text turns, tool requests are answered with a degraded result;
 *  - no memory             -> single-turn conversation;
 *  - no prompt             -> the built-in baseline layers;
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
    /**
     * 工具执行前的硬规则。brain 自己发布它（见 AgentBrainPlugin），其它插件往上挂。
     *
     * 默认空实现，让只测 loop 的用例不必先起一个注册表；接上真实注册表后 vet 一次
     * 是 O(guard 数量) 的内存遍历，不会碰到 I/O。
     */
    private val guards: ToolGuardRegistry = NoGuards,
) : AgentService, org.cordis.api.HudController {

    // ---------------------------------------------------------------- 暂停与停止

    /**
     * 暂停是真的"冻结"：当前这一步跑完之后停在原地，不发起下一轮模型调用。
     *
     * 不在工具执行中间硬停——点了一半的手指不可能收回来，停在动作之间才是干净的断点。
     */
    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    private val pauseLock = Any()
    private var pausedWaiter: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** 最近一次推给胶囊的状态：暂停/恢复时要在它的基础上改，不能丢掉步骤和工具名。 */
    @Volatile
    private var lastHud: org.cordis.api.HudState =
        org.cordis.api.HudState(status = "思考中", phase = org.cordis.api.HudPhase.RUNNING)

    override fun onTogglePause() {
        val nowPaused = synchronized(pauseLock) {
            paused = !paused
            if (!paused) {
                pausedWaiter?.resume(Unit, null)
                pausedWaiter = null
            }
            paused
        }
        val base = lastHud
        publishHud(
            base.copy(
                status = if (nowPaused) "已暂停" else "执行中",
                phase = if (nowPaused) org.cordis.api.HudPhase.PAUSED else org.cordis.api.HudPhase.RUNNING,
            ),
        )
    }

    override fun onStop() {
        stopRequested = true
        // 停止必须能把挂起中的暂停唤醒，否则一颗暂停的胶囊会永远停在那里，
        // 而"停止"看起来毫无反应。
        synchronized(pauseLock) {
            paused = false
            pausedWaiter?.resume(Unit, null)
            pausedWaiter = null
        }
        publishHud(
            lastHud.copy(status = "已停止", phase = org.cordis.api.HudPhase.STOPPED),
        )
    }

    /** 每个断点都过一遍：先响应停止，再处理暂停。 */
    private suspend fun checkpoint() {
        if (stopRequested) throw AgentStopped()
        synchronized(pauseLock) {
            if (!paused) return
        }
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            synchronized(pauseLock) {
                if (!paused || stopRequested) {
                    continuation.resume(Unit, null)
                } else {
                    pausedWaiter = continuation
                    continuation.invokeOnCancellation {
                        synchronized(pauseLock) { pausedWaiter = null }
                    }
                }
            }
        }
        if (stopRequested) throw AgentStopped()
    }

    private fun publishHud(state: org.cordis.api.HudState) {
        lastHud = state
        runCatching { capabilities.current().hud?.show(state) }
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

    /** 用户按了停止。单独一个类型，好和真正的异常区分开。 */
    private class AgentStopped : RuntimeException("用户停止了这一轮")

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
    ): Flow<AgentProgress> = channelFlow {
        val collector = object : FlowCollector<AgentProgress> {
            override suspend fun emit(value: AgentProgress) { send(value) }
        }
        val hud = capabilities.current().hud
        // 新的一轮从干净状态开始：上一轮按下的停止/暂停不该影响这一次。
        stopRequested = false
        paused = false
        try {
            collector.turn(sessionId, text, model, systemPrompt)
        } catch (stopped: AgentStopped) {
            // 胶囊上已经显示"已停止"，这里给对话一个明确的收尾，而不是无声结束。
            send(AgentProgress.Done(degraded(null, "已停止当前任务")))
        } finally {
            // 胶囊跟着这一轮走：正常结束、报错、被取消，任何一条路径都要收掉。
            // 留一颗胶囊挂在屏幕顶上是最难看的失败模式。
            hud?.hide()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The loop itself. Plain `flow` would forbid what streaming needs: text deltas arrive on the
     * pump coroutine inside [ModelService.completeStreaming], and a `FlowCollector` may only be
     * driven from the flow's own coroutine. `channelFlow` gives a buffered channel instead, so an
     * emission from a child coroutine is legal and ordered.
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
        // Everything the loop has to carry from one round to the next lives here: the running
        // prompt, the counters the budget reads, and the fingerprints that detect a repeated
        // call. turnOnce gets it by reference, so it mutates the same values this function reads.
        val state = TurnState(
            memory = current.memory,
            sessionLog = current.sessionLog,
            sessionId = sessionId,
            tools = tools,
            specs = tools?.specs().orEmpty(),
            approval = current.approval,
            policy = current.policy,
        )

        val userMessage = AgentMessage(role = AgentRole.USER, content = text)
        // The behaviour baseline plus the caller's character prompt, as one ordered message. It
        // belongs to this request only: it goes in front of history and the current user message,
        // and is never appended to memory.
        //
        // Volatile layers (the clock, battery, foreground app) are deliberately *not* in that
        // message: prefix caching compares a request from its first token, so a single changing
        // minute in the system message invalidates the cache for the whole history behind it.
        // They ride at the tail instead, appended to this turn's user message.
        val prompt = composePrompt(current.prompt, systemPrompt, sessionId)
        prompt.system?.let { state.inputs += AgentMessage(AgentRole.SYSTEM, it) }
        state.inputs += historyOf(state.memory, state.sessionLog, state.sessionId)
        state.inputs += if (prompt.volatile.isNullOrBlank()) {
            userMessage
        } else {
            AgentMessage(AgentRole.USER, "${text}\n\n${prompt.volatile}")
        }
        // Memory keeps what the user actually typed: the volatile tail is regenerated every turn,
        // and storing it would replay a stale clock in later requests.
        remember(state.memory, state.sessionLog, state.sessionId, userMessage)

        val startedAt = System.nanoTime()
        var stop = AgentStop(AgentStop.Reason.ROUNDS, 0, 0)

        // The skeleton: check the budget, hand one round to [turnOnce], stop when it says so.
        // Everything the user sees per round (RoundStarted, tool events, RoundFinished) is emitted
        // from there, because it belongs to that interaction, not to this loop.
        while (true) {
            val elapsedMs = (System.nanoTime() - startedAt - state.approvalNanos) / 1_000_000
            if (state.rounds >= budget.maxRounds) {
                stop = AgentStop(AgentStop.Reason.ROUNDS, state.rounds, state.toolCalls)
                break
            }
            if (elapsedMs > budget.deadlineMs) {
                stop = AgentStop(AgentStop.Reason.DEADLINE, state.rounds, state.toolCalls)
                break
            }
            if (promptChars(state.inputs) > budget.maxPromptChars) {
                stop = AgentStop(AgentStop.Reason.ROUNDS, state.rounds, state.toolCalls, "上下文超出预算")
                break
            }

            when (val step = turnOnce(state, modelService, target)) {
                // The model call failed and its error reply is already on the wire: this turn has
                // no summary to write and no TurnEnded to log, exactly as before.
                TurnStep.Abort -> return
                is TurnStep.Stop -> {
                    stop = step.stop
                    break
                }
                TurnStep.Continue -> Unit
            }
        }

        val finished = stop.reason == AgentStop.Reason.FINISHED
        val content = if (finished) {
            state.lastReply?.message?.content?.takeIf { it.isNotBlank() } ?: FALLBACK_NO_TEXT
        } else {
            emit(AgentProgress.Finalizing)
            wrapUp(stop, state.inputs, target, modelService, state.usages, state.events)
        }
        if (content.isNotBlank()) {
            remember(
                state.memory,
                state.sessionLog,
                state.sessionId,
                AgentMessage(role = AgentRole.ASSISTANT, content = content),
            )
        }

        // 这一轮为什么停下也要落日志：只存消息的话，回放时看不出它是被预算截断的，
        // 也分不出"模型自己说完"和"我们把它掐了"。
        state.sessionLog?.let { log ->
            try {
                log.append(state.sessionId, SessionEvent.TurnEnded(stop))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // 收尾事件丢了不影响对话本身；下一次读历史仍然完整。
            }
        }

        emit(
            AgentProgress.Done(
                AgentReply(
                    message = AgentMessage(role = AgentRole.ASSISTANT, content = content),
                    model = target,
                    events = state.events,
                    usage = AgentUsageTotals.aggregate(state.usages),
                    stop = stop,
                ),
            ),
        )
    }

    /**
     * One model interaction: pause check, request, run this round's tool batch, feed the results
     * back into [TurnState.inputs].
     *
     * It answers "what happened this round" and nothing else - it does not decide whether the turn
     * goes on ([turn] owns every termination check) and it does not write the final reply. Progress
     * events are emitted here anyway, because in time they belong to this interaction: RoundStarted
     * before the request, ToolStarted/ToolFinished per call, RoundFinished once the results are in.
     */
    private suspend fun FlowCollector<AgentProgress>.turnOnce(
        state: TurnState,
        modelService: ModelService,
        target: ModelRef,
    ): TurnStep {
        // 每轮开头是第一个断点：暂停中的 agent 不该再向模型发起一次调用。
        checkpoint()
        state.rounds++
        emit(AgentProgress.RoundStarted(state.rounds))
        // 等模型写答案这段时间也要有声：胶囊不能只在调工具时亮，
        // 否则"它到底在不在动"这个问题在最长的一段等待里反而没有答案。
        publishHud(
            org.cordis.api.HudState(
                step = state.toolCalls,
                status = if (state.toolCalls == 0) "思考中" else "整理结果",
                phase = org.cordis.api.HudPhase.RUNNING,
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
        remember(state.memory, state.sessionLog, state.sessionId, assistantTurn)

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
            checkpoint()
            // 屏幕上的进度：用户看不见审批弹窗了，得在胶囊上看见"现在在跑哪个工具"。
            // 走 publishHud 而不是直接 show：暂停/停止时要基于最近一次状态改，
            // 直接 show 会让 lastHud 停在旧值上。
            publishHud(
                org.cordis.api.HudState(
                    step = state.toolCalls,
                    tool = call.name,
                    note = describe(call),
                    status = if (call.name.startsWith("screen_")) "操作中" else "执行中",
                    phase = org.cordis.api.HudPhase.RUNNING,
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
                publishHud(lastHud.copy(status = "这一步失败了", note = null))
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
            remember(state.memory, state.sessionLog, state.sessionId, toolMessage)
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
            remember(state.memory, state.sessionLog, state.sessionId, imageMessage)
        }
        // One nudge per round is enough: several identical reminders would just eat context.
        outcomes.filterIsInstance<Outcome.Reflection>().firstOrNull()?.let { nudge ->
            // A system message in the middle of a conversation is rejected or ignored by some
            // OpenAI-compatible servers, so the nudge rides as a user message and says
            // explicitly that it comes from the runtime.
            val message = AgentMessage(AgentRole.USER, nudge.text)
            state.inputs += message
            remember(state.memory, state.sessionLog, state.sessionId, message)
        }

        emit(AgentProgress.RoundFinished(state.rounds))
        // The tool budget or the repeat guard may have fired mid-round; the break itself belongs
        // to [turn], so report the reason instead of returning early.
        val pending = stopAfterRound
        return if (pending != null) TurnStep.Stop(pending) else TurnStep.Continue
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

    /**
     * 历史：事件流优先，memory 兜底。
     *
     * 为什么不是"两边合并"：合并就得定义去重与顺序，而这两份数据本来就该是同一串消息。
     * 日志非空说明它记录了完整的一轮（brain 每次 remember 都先写它），直接采用；
     * 日志为空说明这是启用日志之前的会话——那时只有 memory 有内容，读它就对了。
     * 这样升级到事件流不需要迁移脚本，老会话不会突然"没有历史"。
     */
    private suspend fun historyOf(
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
    private suspend fun remember(
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

    /**
     * The two halves of one request's prompt: the stable system message, and the volatile tail.
     *
     * [volatile] is null when no layer asked for it, which is the case for the built-in baseline -
     * a request whose tail never changes must not grow a "runtime info" section for nothing.
     */
    private data class ComposedPrompt(val system: String?, val volatile: String?)

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
    private fun composePrompt(
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

    /** 没有 guard 的默认实现：让不关心拦截的用例不必先起一个注册表。 */
    internal object NoGuards : ToolGuardRegistry {
        override fun register(guard: ToolGuard): () -> Unit = {}

        override suspend fun vet(call: AgentToolCall): String? = null
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

        /** Room for the whole system message; see [composeSystemPrompt]. */
        const val MAX_SYSTEM_PROMPT_CHARS = 8_000

        /** Counting a heading separator per layer when budgeting the system message. */
        const val PROMPT_LAYER_OVERHEAD_CHARS = 2

        const val PERSONA_ID = "persona"

        /**
         * Header on the volatile tail, so the model can tell it apart from what the user typed.
         *
         * The tail rides inside the user message (a second system message mid-conversation is
         * rejected by some OpenAI-compatible servers), which makes this line the only thing
         * separating "the user said" from "the runtime appended".
         */
        const val VOLATILE_HEADER = "（运行时信息，不是用户输入）"

        /** Between `style` (75) and `env` (40): a persona shapes tone, not behaviour. */
        const val PERSONA_PRIORITY = 60

        /**
         * What the agent is told when no prompt plugin is installed.
         *
         * Short on purpose: it only has to stop the worst failure - an agent that talks about the
         * device instead of operating it, and one that never says when it is done. Everything
         * richer belongs to `agent-prompt`, which the user can replace.
         */
        val BUILTIN_LAYERS: List<PromptLayer> = listOf(
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
