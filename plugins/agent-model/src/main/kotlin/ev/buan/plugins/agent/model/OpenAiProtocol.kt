package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The provider answered, but not in a shape [OpenAiProtocol] can turn into an [AgentReply]. */
class OpenAiFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The OpenAI chat-completions wire format, as two pure functions: request body in, reply out.
 *
 * Everything vendor-specific lives here, at the boundary between the host's [AgentMessage] shape
 * and the JSON an OpenAI-compatible server expects. Keeping it free of OkHttp, Android and
 * `PluginStorage` is what lets the unit tests cover the mapping without a network or a device.
 */
object OpenAiProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Serializes one completion request.
     *
     * [parameters] are optional per-model overrides (`temperature` / `top_p` / `max_tokens`); a
     * null field is omitted so the provider's own default applies. [tools] are added only when
     * there are any: an empty `tools` array is accepted by OpenAI but rejected by some compatible
     * servers, so the field is omitted instead of empty.
     */
    fun requestBody(
        model: String,
        messages: List<AgentMessage>,
        tools: List<ToolSpec> = emptyList(),
        parameters: ModelParameters = ModelParameters(),
    ): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            messages.forEach { message -> add(messageJson(message)) }
        }
        parameters.temperature?.let { put("temperature", it) }
        parameters.topP?.let { put("top_p", it) }
        parameters.maxTokens?.let { put("max_tokens", it) }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool -> add(toolJson(tool)) }
            }
            put("tool_choice", "auto")
        }
    }.toString()

    /**
     * Parses a `chat.completion` response into the vendor-neutral reply.
     *
     * Text becomes [AgentReply.message]; tool requests become both [AgentMessage.toolCalls] on the
     * assistant message and `TOOL_CALL` events (same ids), so the brain can replay the native
     * protocol on the next request while the UI renders progress. A provider that omits a call id
     * gets a synthesized `call_<index>`: the wire protocol requires every `tool` result to cite
     * the id it answers, and null would silently break that chain. The provider's `usage` object
     * becomes [AgentReply.usage]. A provider that reports an error payload or an empty choice list
     * is an [OpenAiFailure]: there is no reply to hand back, and the service layer turns it into a
     * visible assistant message.
     */
    fun parseReply(bodyText: String, requested: ModelRef): AgentReply {
        val root = parseObject(bodyText)
        root["error"]?.let { error -> throw OpenAiFailure(errorMessage(error)) }
        val choices = root.arrayOrNull("choices") ?: throw OpenAiFailure("响应缺少 choices 数组")
        val first = choices.firstOrNull() as? JsonObject ?: throw OpenAiFailure("响应 choices 为空")
        val message = first.objectOrNull("message") ?: throw OpenAiFailure("响应缺少 message 对象")
        val toolCalls = message.arrayOrNull("tool_calls")
            .orEmpty()
            .mapIndexedNotNull { index, element -> toolCallOf(element, index) }
        return AgentReply(
            message = AgentMessage(
                role = AgentRole.ASSISTANT,
                content = contentOf(message).orEmpty(),
                toolCalls = toolCalls,
            ),
            model = ModelRef(provider = requested.provider, model = root.stringOrNull("model") ?: requested.model),
            events = toolCalls.map { call ->
                AgentEvent(
                    kind = AgentEvent.Kind.TOOL_CALL,
                    toolName = call.name,
                    toolCallId = call.id,
                    argumentsJson = call.argumentsJson,
                )
            },
            usage = usageOf(root["usage"]),
        )
    }

    private fun parseObject(bodyText: String): JsonObject =
        try {
            json.parseToJsonElement(bodyText) as? JsonObject
                ?: throw OpenAiFailure("响应不是 JSON 对象")
        } catch (failure: OpenAiFailure) {
            throw failure
        } catch (error: Throwable) {
            throw OpenAiFailure("响应不是合法 JSON：${error.message ?: error::class.simpleName}", error)
        }

    private fun messageJson(message: AgentMessage): JsonObject = buildJsonObject {
        put("role", message.role.wireName())
        // An assistant turn that only calls tools carries no text; strict servers want explicit
        // null content alongside tool_calls rather than an empty string.
        if (message.content.isBlank() && message.toolCalls.isNotEmpty()) {
            put("content", JsonNull)
        } else {
            put("content", message.content)
        }
        message.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        message.toolCallId?.takeIf { it.isNotBlank() }?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call -> add(toolCallJson(call)) }
            }
        }
    }

    private fun toolCallJson(call: AgentToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        putJsonObject("function") {
            put("name", call.name)
            // Arguments must be valid JSON text on the wire; a blank degrades to an empty object,
            // which strict servers accept and an empty string would not.
            put("arguments", call.argumentsJson.ifBlank { "{}" })
        }
    }

    private fun toolJson(tool: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", parametersOf(tool))
        }
    }

    /**
     * `parametersJson` is JSON Schema text supplied by a tools plugin. A malformed schema degrades
     * to an empty object schema instead of failing the whole request: the model then sees a tool
     * without arguments, which is recoverable, while invalid request JSON is not.
     */
    private fun parametersOf(tool: ToolSpec): JsonElement =
        runCatching { json.parseToJsonElement(tool.parametersJson) }.getOrNull()
            ?.takeIf { it is JsonObject }
            ?: JsonObject(emptyMap())

    /**
     * Extracts assistant text. The OpenAI API returns a string, some compatible servers an array
     * of content parts; both are flattened, a null/missing content reads as empty.
     */
    private fun contentOf(message: JsonObject): String? = when (val element = message["content"]) {
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        is JsonArray -> element.mapNotNull { part ->
            (part as? JsonObject)?.stringOrNull("text")
        }.joinToString("")
        else -> null
    }

    private fun toolCallOf(element: JsonElement, index: Int): AgentToolCall? {
        val call = element as? JsonObject ?: return null
        val function = call.objectOrNull("function") ?: return null
        val name = function.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        return AgentToolCall(
            id = call.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: "call_$index",
            name = name,
            argumentsJson = argumentsOf(function["arguments"]).orEmpty(),
        )
    }

    /** Arguments are JSON *text* per the API, but some servers return an already-parsed object. */
    private fun argumentsOf(element: JsonElement?): String? = when (element) {
        is JsonObject, is JsonArray -> element.toString()
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        else -> null
    }

    /**
     * The provider's `usage` object.
     *
     * Missing, non-object, or completely unreadable usage is null. A partially reported object
     * keeps the fields it *did* report; each unknown field stays null rather than becoming 0, so a
     * consumer can distinguish "not reported" from "zero tokens".
     */
    private fun usageOf(element: JsonElement?): AgentUsage? {
        val root = element as? JsonObject ?: return null
        val prompt = root.intOrNull("prompt_tokens")
        val completion = root.intOrNull("completion_tokens")
        val total = root.intOrNull("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return AgentUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
        )
    }

    private fun errorMessage(element: JsonElement): String {
        val detail = (element as? JsonObject)?.stringOrNull("message")
        return (detail ?: element.toString()).take(MAX_ERROR_LENGTH).ifBlank { "模型接口返回未知错误" }
    }

    private fun AgentRole.wireName(): String = when (this) {
        AgentRole.SYSTEM -> "system"
        AgentRole.USER -> "user"
        AgentRole.ASSISTANT -> "assistant"
        AgentRole.TOOL -> "tool"
    }

    private const val MAX_ERROR_LENGTH = 300
}
