package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelEndpointMode
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `providers.json` serialization plus the pure operations the model layer is built from.
 *
 * Keeping encode/decode/merge/migrate/selection here (instead of inside the storage or HTTP class)
 * is what makes the multi-provider layer testable on a plain JVM: the unit tests never touch
 * `PluginStorage`, OkHttp or Android. The file is versioned and permissive on read so an older or
 * hand-edited document degrades to "what can be understood" instead of failing activation.
 */
internal object ProviderDocuments {
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Stable provider order: the catalog first, then custom entries in the order they were saved. */
    fun merge(saved: List<ModelProvider>, catalog: List<ModelProvider>): List<ModelProvider> {
        val byId = saved.filter { it.id.isNotBlank() }.associateBy { it.id }
        val builtinIds = catalog.mapTo(HashSet()) { it.id }
        val builtins = catalog.map { default ->
            val stored = byId[default.id] ?: default
            // `builtIn` and the keyless rule are catalog facts, never user-editable ones.
            val merged = normalize(stored).copy(builtIn = true)
            if (merged.apiKey.isBlank()) merged.copy(enabled = false) else merged
        }
        val customs = saved.filter { it.id.isNotBlank() && it.id !in builtinIds }
            .map { normalize(it).copy(builtIn = false) }
            .distinctBy { it.id }
        return builtins + customs
    }

    /** What actually needs to be written: custom providers and catalog entries the user changed. */
    fun storable(providers: List<ModelProvider>, catalog: List<ModelProvider>): List<ModelProvider> {
        val defaults = catalog.associateBy { it.id }
        return providers.filterNot { provider -> defaults[provider.id]?.let { it == provider } == true }
    }

    /**
     * Migrates the pre-multi-provider `config/model.json` shape: one custom provider named "默认"
     * with its single model selected. The caller persists this next to the catalog.
     */
    fun migrateLegacy(config: ModelConfig): List<ModelProvider> {
        val value = config.normalized()
        return listOf(
            normalize(
                ModelProvider(
                    id = LEGACY_PROVIDER_ID,
                    name = "默认",
                    baseUrl = value.baseUrl,
                    apiKey = value.apiKey,
                    enabled = true,
                    builtIn = false,
                    models = listOf(
                        ModelDescriptor(id = value.model, displayName = value.model, custom = true),
                    ),
                    selectedModelId = value.model,
                ),
            ),
        )
    }

    /**
     * Replaces a provider by id or appends it. `builtIn` is derived from the catalog, not trusted
     * from the caller: a settings page cannot promote a custom provider into a catalog entry.
     */
    fun upsert(
        providers: List<ModelProvider>,
        provider: ModelProvider,
        catalog: List<ModelProvider>,
    ): List<ModelProvider> {
        val normalized = normalize(provider)
        require(normalized.id.isNotBlank()) { "provider.id 不能为空" }
        val isBuiltIn = catalog.any { it.id == normalized.id }
        val stored = normalized.copy(builtIn = isBuiltIn)
        val index = providers.indexOfFirst { it.id == normalized.id }
        return if (index < 0) providers + stored
        else providers.toMutableList().also { it[index] = stored }
    }

    fun delete(providers: List<ModelProvider>, id: String): List<ModelProvider> =
        providers.filterNot { it.id == id }

    /** Restores a catalog default (or drops a custom id, matching [delete] for non-built-ins). */
    fun reset(
        providers: List<ModelProvider>,
        id: String,
        catalog: List<ModelProvider>,
    ): List<ModelProvider> {
        val default = catalog.firstOrNull { it.id == id }
            ?: return providers.filterNot { it.id == id }
        return providers.map { if (it.id == id) default else it }.ifEmpty { listOf(default) }
    }

    /**
     * `ModelService.models` ordering: enabled providers with models only, the selected model of each
     * enabled provider first, then everything else in provider/model order. Consumers such as the
     * agent brain take the first entry as their default.
     */
    fun selectedFirst(providers: List<ModelProvider>): List<ModelRef> {
        val enabled = providers.filter { it.enabled && it.models.isNotEmpty() }
        val ordered = LinkedHashMap<String, ModelRef>()
        enabled.forEach { provider ->
            val selected = provider.selectedModelId
                ?.let { id -> provider.models.firstOrNull { it.id == id } }
            if (selected != null) {
                ordered.putIfAbsent(key(provider.id, selected.id), ModelRef(provider.id, selected.id))
            }
        }
        enabled.forEach { provider ->
            provider.models.forEach { model ->
                ordered.putIfAbsent(key(provider.id, model.id), ModelRef(provider.id, model.id))
            }
        }
        return ordered.values.toList()
    }

    /**
     * Why a provider cannot be called yet (blank/malformed base URL), or null when it can.
     *
     * The settings page may save a half-filled custom provider; requests must then fail with a
     * readable message instead of letting OkHttp throw an `IllegalArgumentException` for a URL
     * that is not absolute.
     */
    fun baseUrlError(baseUrl: String): String? {
        val value = baseUrl.trim()
        if (value.isEmpty()) return "未配置接口地址"
        val scheme = value.substringBefore("://", missingDelimiterValue = "")
        if (scheme != "http" && scheme != "https") {
            return "接口地址必须以 http:// 或 https:// 开头"
        }
        if (value.substringAfter("://").substringBefore('/').isBlank()) {
            return "接口地址缺少主机名"
        }
        return null
    }

    /**
     * Headers of every request to [provider]: user headers first, then `Authorization` from the API
     * key (an explicit user header wins) and an `Accept` default. Content-Type is the transport's.
     */
    fun requestHeaders(provider: ModelProvider): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        provider.headers.forEach { (name, value) ->
            if (name.isNotBlank()) headers[name] = value
        }
        if (provider.apiKey.isNotBlank()) {
            headers.putIfAbsent(AUTHORIZATION, "Bearer ${provider.apiKey.trim()}")
        }
        headers.putIfAbsent(ACCEPT, "application/json")
        return headers
    }

    fun encode(providers: List<ModelProvider>): String = buildJsonObject {
        put("version", VERSION)
        putJsonArray("providers") { providers.forEach { add(providerJson(it)) } }
    }.toString()

    /** Null when the document is not JSON or has no provider array; entries may be skipped. */
    fun decode(text: String): List<ModelProvider>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root.arrayOrNull("providers") ?: return null
            else -> return null
        }
        return array.mapNotNull { element -> (element as? JsonObject)?.let(::providerOf) }
    }

    fun normalize(provider: ModelProvider): ModelProvider {
        val id = provider.id.trim()
        return provider.copy(
            id = id,
            name = provider.name.trim().ifEmpty { id },
            baseUrl = provider.baseUrl.trim().trimEnd('/'),
            apiKey = provider.apiKey.trim(),
            headers = provider.headers
                .filterKeys { it.isNotBlank() }
                .mapValues { (_, value) -> value.trim() },
            models = provider.models.mapNotNull { it.normalized() }.distinctBy { it.id },
            selectedModelId = provider.selectedModelId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun ModelDescriptor.normalized(): ModelDescriptor? {
        val id = id.trim()
        if (id.isEmpty()) return null
        return copy(
            id = id,
            displayName = displayName.trim().ifEmpty { id },
            contextWindow = contextWindow?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun key(providerId: String, modelId: String): String = "$providerId\u0000$modelId"

    private fun providerJson(provider: ModelProvider): JsonObject = buildJsonObject {
        put("id", provider.id)
        put("name", provider.name)
        put("baseUrl", provider.baseUrl)
        put("apiKey", provider.apiKey)
        put("endpointMode", provider.endpointMode.name)
        putJsonObject("headers") {
            provider.headers.forEach { (name, value) -> put(name, value) }
        }
        put("enabled", provider.enabled)
        put("builtIn", provider.builtIn)
        putJsonArray("models") { provider.models.forEach { add(modelJson(it)) } }
        provider.selectedModelId?.let { put("selectedModelId", it) }
    }

    private fun modelJson(model: ModelDescriptor): JsonObject = buildJsonObject {
        put("id", model.id)
        put("displayName", model.displayName)
        model.contextWindow?.let { put("contextWindow", it) }
        put("supportsThinking", model.supportsThinking)
        putJsonObject("parameters") {
            model.parameters.temperature?.let { put("temperature", it) }
            model.parameters.topP?.let { put("topP", it) }
            model.parameters.maxTokens?.let { put("maxTokens", it) }
        }
        put("custom", model.custom)
    }

    private fun providerOf(root: JsonObject): ModelProvider? {
        val id = root.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ModelProvider(
            id = id,
            name = root.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            baseUrl = root.stringOrNull("baseUrl").orEmpty(),
            apiKey = root.stringOrNull("apiKey").orEmpty(),
            endpointMode = root.stringOrNull("endpointMode")
                ?.let { name -> ModelEndpointMode.entries.firstOrNull { it.name == name } }
                ?: ModelEndpointMode.CHAT_COMPLETIONS,
            headers = headersOf(root.objectOrNull("headers")),
            enabled = root.booleanOrNull("enabled") ?: true,
            builtIn = root.booleanOrNull("builtIn") ?: false,
            models = root.arrayOrNull("models").orEmpty()
                .mapNotNull { element -> (element as? JsonObject)?.let(::modelOf) }
                .distinctBy { it.id },
            selectedModelId = root.stringOrNull("selectedModelId")?.takeIf { it.isNotBlank() },
        )
    }

    private fun modelOf(root: JsonObject): ModelDescriptor? {
        val id = root.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ModelDescriptor(
            id = id,
            displayName = root.stringOrNull("displayName")?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            contextWindow = root.stringOrNull("contextWindow")?.trim()?.takeIf { it.isNotEmpty() },
            supportsThinking = root.booleanOrNull("supportsThinking") ?: false,
            parameters = root.objectOrNull("parameters")?.let(::parametersOf) ?: ModelParameters(),
            custom = root.booleanOrNull("custom") ?: false,
        )
    }

    private fun parametersOf(root: JsonObject): ModelParameters = ModelParameters(
        temperature = root.doubleOrNull("temperature"),
        topP = root.doubleOrNull("topP"),
        maxTokens = root.intOrNull("maxTokens"),
    )

    private fun headersOf(root: JsonObject?): Map<String, String> {
        if (root == null) return emptyMap()
        val headers = LinkedHashMap<String, String>()
        root.forEach { (name, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name.isNotBlank() && text != null) headers[name] = text
        }
        return headers
    }

    private const val LEGACY_PROVIDER_ID = "default"
    private const val AUTHORIZATION = "Authorization"
    private const val ACCEPT = "Accept"
}
