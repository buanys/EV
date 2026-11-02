package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelRef
import java.util.TreeMap
import kotlinx.serialization.json.JsonObject

/**
 * Reassembles a `chat.completion.chunk` stream into the same [AgentReply] the non-streaming call
 * would have produced.
 *
 * The wire shape is deliberately lossy: text arrives as fragments, and a tool call arrives in
 * pieces (`id` once, `name` once, `arguments` character by character) keyed by an index. So this
 * holds the accumulating state and answers two questions: [feed] returns the *new* text (what the
 * UI shows as it types), and [reply] returns the finished message the loop needs before the next
 * round.
 *
 * It never invents anything the server did not send: an absent `usage`, `model` or `finish_reason`
 * stays absent, exactly like [OpenAiProtocol.parseReply].
 */
internal class OpenAiStream(private val requested: ModelRef) {

    private val text = StringBuilder()
    private val calls = TreeMap<Int, PendingCall>()
    private var usage: AgentUsage? = null
    private var model: String? = null
    private var finishReason: String? = null

    /** One `data:` payload in; the freshly produced text out (null when this chunk carried none). */
    fun feed(payload: String): String? {
        val root = OpenAiProtocol.parseObject(payload)
        root["error"]?.let { error -> throw OpenAiFailure(OpenAiProtocol.errorMessage(error)) }
        root.stringOrNull("model")?.takeIf { it.isNotBlank() }?.let { model = it }
        OpenAiProtocol.usageOf(root["usage"])?.let { usage = it }

        val choice = root.arrayOrNull("choices")?.firstOrNull() as? JsonObject ?: return null
        choice.stringOrNull("finish_reason")?.let { finishReason = it }
        val delta = choice.objectOrNull("delta") ?: return null

        delta.arrayOrNull("tool_calls").orEmpty().forEach { element ->
            val call = element as? JsonObject ?: return@forEach
            val index = call.intOrNull("index") ?: calls.size
            val pending = calls.getOrPut(index) { PendingCall(index) }
            call.stringOrNull("id")?.takeIf { it.isNotBlank() }?.let { pending.id = it }
            call.objectOrNull("function")?.let { function ->
                function.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let { pending.name = it }
                // Arguments stream in fragments and must be concatenated verbatim: parsing them
                // piecewise would fail, and "{}" would lose a partially streamed object.
                OpenAiProtocol.argumentsOf(function["arguments"])?.let { pending.arguments.append(it) }
            }
        }

        val piece = OpenAiProtocol.contentOf(delta).orEmpty()
        if (piece.isEmpty()) return null
        text.append(piece)
        return piece
    }

    /** The finished reply. Tool calls are ordered by their stream index, which is the call order. */
    fun reply(): AgentReply {
        val toolCalls = calls.values.mapNotNull { pending ->
            val name = pending.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentToolCall(
                id = pending.id?.takeIf { it.isNotBlank() } ?: "call_${pending.index}",
                name = name,
                argumentsJson = pending.arguments.toString(),
            )
        }
        return AgentReply(
            message = AgentMessage(
                role = AgentRole.ASSISTANT,
                content = text.toString(),
                toolCalls = toolCalls,
            ),
            model = ModelRef(provider = requested.provider, model = model ?: requested.model),
            events = toolCalls.map { call ->
                AgentEvent(
                    kind = AgentEvent.Kind.TOOL_CALL,
                    toolName = call.name,
                    toolCallId = call.id,
                    argumentsJson = call.argumentsJson,
                )
            },
            usage = usage,
        )
    }

    /** `finish_reason` as the server reported it; null when the stream ended without one. */
    fun finishReason(): String? = finishReason

    private class PendingCall(val index: Int) {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
