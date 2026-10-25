package ev.buan.plugins.agent.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pre-multi-provider configuration: one endpoint plus the single model the user picked there.
 *
 * Nothing writes this shape any more; [ProviderStore] reads `config/model.json` once to migrate an
 * existing install into the provider list ([ProviderDocuments.migrateLegacy]). The type and its
 * codec stay because migration must be able to parse exactly what older versions wrote.
 *
 * [apiKey] may be blank on purpose: local OpenAI-compatible servers (Ollama, LM Studio, vLLM
 * usually run without auth) do not need one.
 */
data class ModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    /** Trims pasted whitespace and drops the trailing slash so [endpoint] adds exactly one path. */
    fun normalized(): ModelConfig = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        model = model.trim(),
    )

    /** Why this config cannot be used yet, or null when it is complete enough to call. */
    fun validationError(): String? {
        val value = normalized()
        val scheme = value.baseUrl.substringBefore("://", missingDelimiterValue = "")
        if (scheme != "http" && scheme != "https") {
            return "接口地址必须以 http:// 或 https:// 开头"
        }
        if (value.baseUrl.substringAfter("://").substringBefore('/').isBlank()) {
            return "接口地址缺少主机名"
        }
        if (value.model.isBlank()) return "模型名不能为空"
        return null
    }

    /**
     * Full chat-completions URL. The OpenAI convention is a base ending in the API version
     * (`.../v1`), with the operation appended per call.
     */
    val endpoint: String get() = normalized().baseUrl + "/chat/completions"
}

/**
 * The stored form of the legacy [ModelConfig], kept separate from the data class so both directions
 * are pure functions the unit tests can exercise without `PluginStorage`.
 *
 * Decoding is deliberately forgiving: an unparsable or incomplete file means "nothing to migrate"
 * instead of a plugin that fails to activate.
 */
object ModelConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: ModelConfig): String {
        val value = config.normalized()
        return buildJsonObject {
            put("baseUrl", value.baseUrl)
            put("apiKey", value.apiKey)
            put("model", value.model)
        }.toString()
    }

    fun decode(text: String): ModelConfig? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val baseUrl = root.stringOrNull("baseUrl") ?: return null
        val model = root.stringOrNull("model") ?: return null
        val config = ModelConfig(
            baseUrl = baseUrl,
            apiKey = root.stringOrNull("apiKey").orEmpty(),
            model = model,
        ).normalized()
        return config.takeIf { it.validationError() == null }
    }
}
