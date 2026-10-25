package ev.buan.plugins.ev.ui.ui

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelRef
import java.util.UUID

/**
 * The model-provider page's state and editing rules, kept out of the composables so they can be
 * tested on a plain JVM.
 *
 * The contract ([ModelProviderService]) only reads and writes whole providers; everything that makes
 * the page usable - filtering, summaries, "which model is the chat actually using", header rows,
 * parameter validation, keeping custom models across a refresh - is a pure function of that data and
 * lives here.
 */
internal sealed interface ProvidersState {
    /** No plugin publishes a [ModelProviderService]: agent-model is missing or disabled. */
    data object Missing : ProvidersState

    data class Loaded(val providers: List<ModelProvider>) : ProvidersState

    /** The service exists but reading it threw; the message is shown instead of crashing the page. */
    data class Failed(val message: String) : ProvidersState
}

internal fun readProviders(service: () -> ModelProviderService?): ProvidersState {
    val found = try {
        service()
    } catch (error: Exception) {
        // A failing lookup is reported, not folded into "not installed": those are different fixes.
        return ProvidersState.Failed(providerError(error))
    } ?: return ProvidersState.Missing
    return try {
        ProvidersState.Loaded(found.providers())
    } catch (error: Exception) {
        ProvidersState.Failed(providerError(error))
    }
}

internal fun providerError(error: Throwable): String =
    error.message ?: error::class.simpleName ?: "未知错误"

internal fun filterProviders(providers: List<ModelProvider>, query: String): List<ModelProvider> {
    val text = query.trim()
    if (text.isEmpty()) return providers
    return providers.filter { provider ->
        provider.name.contains(text, ignoreCase = true) ||
            provider.id.contains(text, ignoreCase = true) ||
            provider.baseUrl.contains(text, ignoreCase = true)
    }
}

internal fun filterModels(models: List<ModelDescriptor>, query: String): List<ModelDescriptor> {
    val text = query.trim()
    if (text.isEmpty()) return models
    return models.filter { model ->
        model.id.contains(text, ignoreCase = true) ||
            model.displayName.contains(text, ignoreCase = true) ||
            model.contextWindow?.contains(text, ignoreCase = true) == true
    }
}

/**
 * Which provider/model the chat actually uses.
 *
 * Mirrors the ordering rule the contract states for `ModelService.models`: enabled providers with
 * models first, and within them the provider whose [ModelProvider.selectedModelId] is set. The page
 * cannot see the runtime service, so it recomputes the same "first entry" to put a "使用中" marker on
 * the right row instead of marking every provider that has a selection.
 */
internal fun currentModel(providers: List<ModelProvider>): ModelRef? {
    val enabled = providers.filter { it.enabled && it.models.isNotEmpty() }
    enabled.forEach { provider ->
        val selected = provider.selectedModelId
        if (selected != null && provider.models.any { it.id == selected }) {
            return ModelRef(provider.id, selected)
        }
    }
    val first = enabled.firstOrNull() ?: return null
    val model = first.models.firstOrNull() ?: return null
    return ModelRef(first.id, model.id)
}

internal fun providerSummary(provider: ModelProvider, current: ModelRef?): String = buildList {
    if (provider.builtIn) add("内置") else add("自定义")
    if (!provider.enabled) add("已禁用")
    if (current?.provider == provider.id) add("使用中")
    add(if (provider.apiKey.isBlank()) "未配置 Key" else "已配置 Key")
    add(provider.baseUrl.ifBlank { "未填写 Base URL" })
    add("${provider.models.size} 个模型")
}.joinToString(" · ")

internal fun modelSummary(model: ModelDescriptor, provider: ModelProvider, current: ModelRef?): String = buildList {
    add(model.id)
    model.contextWindow?.let { add(it) }
    if (model.supportsThinking) add("支持思考")
    if (model.custom) add("自定义")
    if (current?.provider == provider.id && current.model == model.id) {
        add("当前使用")
    } else if (provider.selectedModelId == model.id) {
        add("已选中")
    }
}.joinToString(" · ")

internal fun parametersSummary(parameters: ModelParameters): String {
    val parts = buildList {
        parameters.temperature?.let { add("temperature $it") }
        parameters.topP?.let { add("topP $it") }
        parameters.maxTokens?.let { add("maxTokens $it") }
    }
    return if (parts.isEmpty()) "默认参数" else parts.joinToString(" · ")
}

/** A blank custom provider for the "new" page; the id is minted before the user types anything. */
internal fun newCustomProvider(): ModelProvider = ModelProvider(
    id = "custom-${UUID.randomUUID().toString().take(8)}",
    name = "",
    baseUrl = "",
    enabled = true,
    builtIn = false,
)

/** Why the draft cannot be saved yet, or null when it can. Mirrors the model plugin's own rules. */
internal fun providerDraftError(provider: ModelProvider): String? {
    if (provider.name.isBlank()) return "名称不能为空"
    val baseUrl = provider.baseUrl.trim()
    if (baseUrl.isEmpty()) return "Base URL 不能为空"
    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
        return "Base URL 必须以 http:// 或 https:// 开头"
    }
    if (baseUrl.substringAfter("://").substringBefore('/').isBlank()) return "Base URL 缺少主机名"
    return null
}

/** One editable header row; a map cannot keep the empty key a user is halfway through typing. */
internal data class HeaderRow(val key: String, val value: String)

internal fun headerRows(headers: Map<String, String>): List<HeaderRow> =
    headers.map { (key, value) -> HeaderRow(key, value) }

/** Blank keys are dropped; a later duplicate wins, which is what "the last one you typed" means. */
internal fun headerMap(rows: List<HeaderRow>): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    rows.forEach { row ->
        val key = row.key.trim()
        if (key.isNotEmpty()) headers[key] = row.value.trim()
    }
    return headers
}

/**
 * The providers to save so that [modelId] of [providerId] becomes the chat's model: the target gets
 * the selection and every other provider loses it, because the contract resolves "current" as the
 * first selected model in provider order. Only changed providers are returned.
 */
internal fun selectCurrentModel(
    providers: List<ModelProvider>,
    providerId: String,
    modelId: String,
): List<ModelProvider> = providers.mapNotNull { provider ->
    when {
        provider.id == providerId && provider.selectedModelId != modelId -> provider.copy(selectedModelId = modelId)
        provider.id != providerId && provider.selectedModelId != null -> provider.copy(selectedModelId = null)
        else -> null
    }
}

internal fun upsertModel(models: List<ModelDescriptor>, model: ModelDescriptor): List<ModelDescriptor> {
    val index = models.indexOfFirst { it.id == model.id }
    return if (index < 0) models + model else models.toMutableList().also { it[index] = model }
}

/** Drops [modelId]; if it was the provider's pick the selection goes with it. */
internal fun removeModel(provider: ModelProvider, modelId: String): ModelProvider = provider.copy(
    models = provider.models.filterNot { it.id == modelId },
    selectedModelId = provider.selectedModelId?.takeIf { it != modelId },
)

/**
 * Applies a `/models` refresh: the provider's own list replaces non-custom models, while models the
 * user added by hand survive (the contract marks them `custom` for exactly this reason).
 */
internal fun mergeFetchedModels(
    provider: ModelProvider,
    fetched: List<ModelDescriptor>,
): ModelProvider {
    val customModels = provider.models.filter { it.custom }
    val fetchedModels = fetched.map { it.copy(custom = false) }
    val merged = (fetchedModels + customModels).distinctBy { it.id }
    return provider.copy(
        models = merged,
        selectedModelId = provider.selectedModelId?.takeIf { id -> merged.any { it.id == id } },
    )
}

internal sealed interface ParametersParse {
    data class Valid(val parameters: ModelParameters) : ParametersParse

    data class Invalid(val message: String) : ParametersParse
}

/**
 * Parses the three parameter fields; a blank field means "do not send it" (the server default wins).
 * Ranges match the OpenAI-compatible APIs so a typo is caught here instead of as a 400 later.
 */
internal fun parseModelParameters(
    temperature: String,
    topP: String,
    maxTokens: String,
): ParametersParse {
    val temperatureValue = temperature.trim().takeIf { it.isNotEmpty() }?.let { text ->
        val value = text.toDoubleOrNull() ?: return ParametersParse.Invalid("temperature 必须是数字")
        if (value < 0.0 || value > 2.0) return ParametersParse.Invalid("temperature 需要在 0 到 2 之间")
        return@let value
    }
    val topPValue = topP.trim().takeIf { it.isNotEmpty() }?.let { text ->
        val value = text.toDoubleOrNull() ?: return ParametersParse.Invalid("topP 必须是数字")
        if (value < 0.0 || value > 1.0) return ParametersParse.Invalid("topP 需要在 0 到 1 之间")
        return@let value
    }
    val maxTokensValue = maxTokens.trim().takeIf { it.isNotEmpty() }?.let { text ->
        val value = text.toIntOrNull() ?: return ParametersParse.Invalid("maxTokens 必须是整数")
        if (value <= 0) return ParametersParse.Invalid("maxTokens 需要大于 0")
        return@let value
    }
    return ParametersParse.Valid(
        ModelParameters(temperature = temperatureValue, topP = topPValue, maxTokens = maxTokensValue),
    )
}

internal fun modelDraftError(displayName: String, modelId: String): String? = when {
    modelId.isBlank() -> "Model ID 不能为空"
    modelId.contains(' ') -> "Model ID 不能包含空格"
    displayName.isBlank() -> "展示名不能为空"
    else -> null
}
