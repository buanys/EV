package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelTestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pure interpretation of the two `/models` responses the provider page cares about.
 *
 * A provider's `/models` shape is "OpenAI-compatible" in intent but not identical everywhere:
 * the entries may carry `name`, `context_length` or `context_window`, and failures answer with
 * an `error.message` object or plain text. Everything here degrades instead of throwing so
 * [testResult] can always show something useful.
 */
internal object ProviderProbes {
    private val json = Json { ignoreUnknownKeys = true }

    /** The remote model list, or null when the body is not an OpenAI-style `{ "data": [...] }`. */
    fun parseRemoteModels(bodyText: String): List<ModelDescriptor>? {
        val root = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull() ?: return null
        val data = root.arrayOrNull("data") ?: return null
        return data.mapNotNull { element -> descriptorOf(element) }.distinctBy { it.id }
    }

    /** Result of `GET /models`: success carries the model count when the list parses. */
    fun testResult(response: ProviderResponse): ModelTestResult {
        if (response.code !in 200..299) {
            return ModelTestResult(
                success = false,
                message = "连接失败（HTTP ${response.code}）：${errorText(response.body)}",
            )
        }
        val models = parseRemoteModels(response.body)
        return ModelTestResult(
            success = true,
            message = if (models == null) {
                "连接成功（HTTP ${response.code}），但响应不是标准的 /models 列表"
            } else {
                "连接成功，可用模型 ${models.size} 个"
            },
        )
    }

    private fun descriptorOf(element: JsonElement): ModelDescriptor? {
        val root = element as? JsonObject ?: return null
        val id = root.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val displayName = root.stringOrNull("name")
            ?: root.stringOrNull("display_name")
            ?: id
        val contextWindow = root.intOrNull("context_length")
            ?: root.intOrNull("context_window")
            ?: root.intOrNull("max_context_length")
        return ModelDescriptor(
            id = id,
            displayName = displayName.trim().ifEmpty { id },
            contextWindow = contextWindow?.toString(),
            supportsThinking = root.booleanOrNull("supports_thinking") ?: false,
            custom = false,
        )
    }

    /** `error.message` when the body is an error object, else the trimmed body, bounded for a row. */
    private fun errorText(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val detail = root?.objectOrNull("error")?.stringOrNull("message")
            ?: root?.stringOrNull("message")
            ?: root?.stringOrNull("detail")
            ?: body.takeIf { it.isNotBlank() }
            ?: "无响应内容"
        return detail.trim().take(MAX_MESSAGE_LENGTH).ifBlank { "无响应内容" }
    }

    private const val MAX_MESSAGE_LENGTH = 200
}
