package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolGuard
import ev.buan.plugins.agent.api.ToolGuardRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn


/**
 * The conversation brain: one [AgentService] over four optional capability plugins.
 *
 * A turn is `system? -> memory.history -> model.complete -> (tool calls) -> model.complete ->
 * memory.append`. The system message is assembled per turn from the `agent/system-prompt` plugin's
 * layers plus the caller's character prompt (see [composePrompt]): it goes ahead of history
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

    /**
     * 每轮循环体（[TurnRunner.turnOnce]）和它的纯函数帮手都住在 [TurnRunner] 里；
     * 这里只注入宿主才有的操作：暂停/停止断点、消息持久化、胶囊 HUD。
     */
    private val turnRunner = TurnRunner(budget, guards, object : TurnRunner.Host {
        override suspend fun checkpoint() = this@AgentBrainService.checkpoint()
        override suspend fun remember(
            memory: MemoryService?,
            sessionLog: SessionLog?,
            sessionId: String,
            message: AgentMessage,
        ) = recordMessage(memory, sessionLog, sessionId, message)
        override fun publishHud(state: org.cordis.api.HudState) = this@AgentBrainService.publishHud(state)
        override fun currentHud(): org.cordis.api.HudState = lastHud
    })

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
        recordMessage(state.memory, state.sessionLog, state.sessionId, userMessage)

        val startedAt = System.nanoTime()
        var stop = AgentStop(AgentStop.Reason.ROUNDS, 0, 0)

        // The skeleton: check the budget, hand one round to [TurnRunner.turnOnce], stop when it
        // says so. Everything the user sees per round (RoundStarted, tool events, RoundFinished)
        // is emitted from there, because it belongs to that interaction, not to this loop.
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

            when (val step = with(turnRunner) { turnOnce(state, modelService, target) }) {
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
            recordMessage(
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

    private companion object {
        const val MODEL_PLUGIN = "ev.buan.plugins.agent.model"

        /**
         * Bounds of the model-facing history view (the stored log itself is never trimmed):
         * enough context for continuity, small enough that tool rounds cannot blow up the window.
         */
        const val MAX_HISTORY_TURNS = 20
        const val MAX_HISTORY_CHARS = 24_000

        /** Summary call after a budget stop. */
        const val WRAP_TIMEOUT_MS = 60_000L

        /** ... and after a DEADLINE stop, where the budget is already spent. */
        const val WRAP_TIMEOUT_AFTER_DEADLINE_MS = 15_000L

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
