package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Usage of every model call in the turn is aggregated into [AgentReply.usage] (see
 * [AgentUsageTotals]); a turn whose calls reported nothing has no usage.
 */
class AgentBrainService(
    private val capabilities: CapabilityProvider,
) : AgentService {

    /** True when a model plugin is published and serves at least one configured model. */
    override val ready: Boolean
        get() = try {
            capabilities.current().model?.models?.isNotEmpty() == true
        } catch (error: Throwable) {
            false
        }

    override suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): AgentReply = withContext(Dispatchers.IO) {
        val current = capabilities.current()
        val modelService = current.model ?: return@withContext degraded(
            model,
            "未连接模型插件：请在插件管理页启用 $MODEL_PLUGIN，授予 NETWORK / PLUGIN_STORAGE 并完成配置。",
        )
        val available = modelService.models
        if (available.isEmpty()) return@withContext degraded(
            model,
            "模型插件已启用但尚未配置：打开插件管理 → 模型服务，填写接口地址、API Key 与模型名后保存。",
        )
        val target = selectModel(available, model) ?: return@withContext degraded(
            model,
            "未找到模型 ${model?.model}；当前可用：${available.joinToString("、") { it.model }}。",
        )

        val memory = current.memory
        val userMessage = AgentMessage(role = AgentRole.USER, content = text)
        // A role instruction belongs to this request only: it goes in front of history and is never
        // handed to memory, so it cannot leak into the stored conversation or later turns.
        val systemMessage = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { AgentMessage(role = AgentRole.SYSTEM, content = it) }
        // The stored log is complete; only the model's view is bounded (see HistoryTrim).
        val history = HistoryTrim.trim(
            historyOf(memory, sessionId),
            maxTurns = MAX_HISTORY_TURNS,
            maxChars = MAX_HISTORY_CHARS,
        )
        val inputs = listOfNotNull(systemMessage) + history + userMessage
        remember(memory, sessionId, userMessage)

        val tools = current.tools
        val specs: List<ToolSpec> = tools?.specs().orEmpty()
        val events = mutableListOf<AgentEvent>()
        val usages = mutableListOf<AgentUsage?>()
        var inputsForModel = inputs
        var last: AgentReply? = null
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val reply = try {
                modelService.complete(target, inputsForModel, specs)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // The successful earlier rounds still consumed tokens; report what was measured.
                return@withContext failure(target, error, AgentUsageTotals.aggregate(usages))
            }
            events += reply.events
            usages += reply.usage
            last = reply
            val calls = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_CALL }
            if (calls.isEmpty()) break
            // Native protocol: the assistant's tool-call turn goes back ahead of the results, so
            // the provider sees its own calls followed by the tool messages that answer them.
            val toolCalls = calls.mapIndexed { index, call -> toolCallOf(index, call) }
            val assistantTurn = reply.message.copy(toolCalls = toolCalls)
            inputsForModel = inputsForModel + assistantTurn
            remember(memory, sessionId, assistantTurn)
            execute(toolCalls, tools, current.approval, events).forEach { outcome ->
                val toolMessage = AgentMessage(
                    role = AgentRole.TOOL,
                    content = outcome.result,
                    name = outcome.name,
                    toolCallId = outcome.id,
                )
                inputsForModel = inputsForModel + toolMessage
                remember(memory, sessionId, toolMessage)
            }
        }

        val reply = last ?: return@withContext degraded(model, "模型没有返回任何结果，请稍后重试。")
        val content = reply.message.content.ifBlank {
            if (events.any { it.kind == AgentEvent.Kind.TOOL_RESULT }) {
                "工具已执行，但模型没有给出文字总结。"
            } else {
                "模型没有返回文本。"
            }
        }
        if (reply.message.content.isNotBlank()) {
            remember(memory, sessionId, AgentMessage(role = AgentRole.ASSISTANT, content = content))
        }
        reply.copy(
            message = AgentMessage(role = AgentRole.ASSISTANT, content = content),
            events = events,
            usage = AgentUsageTotals.aggregate(usages),
        )
    }

    /** Null when the caller asked for a model this provider does not serve. */
    private fun selectModel(available: List<ModelRef>, requested: ModelRef?): ModelRef? =
        if (requested == null) available.firstOrNull()
        else available.firstOrNull { it.model == requested.model }

    private suspend fun historyOf(memory: MemoryService?, sessionId: String): List<AgentMessage> {
        if (memory == null) return emptyList()
        return try {
            memory.history(sessionId)
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
     * Runs every requested tool and records a `TOOL_RESULT` event for each, including failures.
     * A missing registry or an unknown tool becomes result *text* for the next model turn instead
     * of an exception, so a model hallucinating a tool name cannot abort the conversation.
     *
     * Dangerous tools go through the approval gate first (fail-closed when none is published);
     * the refusal is result text too, so the model can explain instead of retrying blindly.
     */
    private suspend fun execute(
        calls: List<AgentToolCall>,
        registry: ToolRegistry?,
        approval: ApprovalService?,
        events: MutableList<AgentEvent>,
    ): List<ToolOutcome> = calls.map { call ->
        val rawResult = when {
            registry == null -> "工具系统未启用（未安装 $TOOLS_PLUGIN）"
            else -> {
                val tool = registry.lookup(call.name)
                if (tool == null) {
                    "没有名为 ${call.name} 的工具"
                } else {
                    invokeGuarded(tool, call, approval)
                }
            }
        }
        val result = truncateUtf16Safe(rawResult, MAX_TOOL_RESULT_CHARS)
        events += AgentEvent(
            kind = AgentEvent.Kind.TOOL_RESULT,
            text = result,
            toolName = call.name,
            toolCallId = call.id,
        )
        ToolOutcome(call.id, call.name, result)
    }

    /**
     * The one place a tool actually runs. A dangerous spec means the model's request is not
     * enough: no gate published refuses outright (fail-closed - the human's voice is absent, not
     * merely quiet), a denial returns the refusal as the result, and only an explicit allow runs
     * the tool. Ordinary tools skip all of this.
     */
    private suspend fun invokeGuarded(
        tool: Tool,
        call: AgentToolCall,
        approval: ApprovalService?,
    ): String {
        if (tool.spec.dangerous) {
            val gate = approval ?: return "已拦截：${call.name} 属于高危操作，需要人工审批，但审批界面未启用（请启用 eta-ui 后重试）"
            val decision = try {
                gate.request(call.name, call.argumentsJson)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // A broken gate must never become an approval.
                return "已拦截：审批通道出错（${error.message ?: error::class.simpleName}），本次未执行"
            }
            if (decision == ApprovalDecision.DENY) return "用户拒绝了本次执行"
        }
        return try {
            tool.invoke(call.argumentsJson)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            "工具调用失败：${error.message ?: error::class.simpleName}"
        }
    }

    private fun degraded(model: ModelRef?, reason: String, usage: AgentUsage? = null): AgentReply =
        AgentReply(
            message = AgentMessage(role = AgentRole.ASSISTANT, content = reason),
            model = model,
            events = listOf(AgentEvent(kind = AgentEvent.Kind.ERROR, text = reason)),
            usage = usage,
        )

    private fun failure(model: ModelRef, error: Throwable, usage: AgentUsage?): AgentReply = degraded(
        model,
        "模型调用失败：${error.message ?: error::class.simpleName}",
        usage,
    )

    private data class ToolOutcome(val id: String, val name: String, val result: String)

    private companion object {
        const val MODEL_PLUGIN = "ev.buan.plugins.agent.model"
        const val TOOLS_PLUGIN = "ev.buan.plugins.agent.tools"

        /** One user turn may ask for tools at most this many times; stops a model that loops. */
        const val MAX_TOOL_ROUNDS = 3

        /** Tool output is fed back into the prompt, so one file cannot swallow the context window. */
        const val MAX_TOOL_RESULT_CHARS = 8_000

        /**
         * Bounds of the model-facing history view (the stored log itself is never trimmed):
         * enough context for continuity, small enough that tool rounds cannot blow up the window.
         */
        const val MAX_HISTORY_TURNS = 20
        const val MAX_HISTORY_CHARS = 24_000
    }
}
