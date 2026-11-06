package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/** How a [ModelProvider] is spoken to. Only the OpenAI chat-completions shape exists today. */
enum class ModelEndpointMode {
    CHAT_COMPLETIONS,
}

/**
 * Per-model call parameters, applied to every request for that model.
 *
 * Null means "do not send this field" - the server's own default wins. Kept as plain nullable
 * values so a provider plugin can map them onto its wire format without a vendor type.
 */
data class ModelParameters(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
)

/**
 * One model a provider can serve, together with what the UI needs to describe it.
 *
 * [id] is the wire name sent to the provider. [displayName] is presentation only. [contextWindow]
 * is a short human-readable label ("128K") because providers disagree on units and the exact number
 * is not used for trimming here. [custom] marks a model the user added by hand rather than one
 * returned by the provider's own `/models` endpoint; a refresh must not delete those.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String = id,
    val contextWindow: String? = null,
    val supportsThinking: Boolean = false,
    val parameters: ModelParameters = ModelParameters(),
    val custom: Boolean = false,
)

/**
 * One configured OpenAI-compatible endpoint and the models the user selected on it.
 *
 * [builtIn] providers come from the model plugin's own catalog: they cannot be deleted, only reset.
 * Custom providers are fully user-owned. [enabled] is the user's switch; the model plugin also
 * disables a built-in provider that has no [apiKey], because every catalog entry is a commercial
 * endpoint that would only answer with 401. [headers] are merged into every request (a user-supplied
 * `Authorization` header wins over [apiKey]). [selectedModelId] is the provider's current pick; the
 * model service orders that model first for consumers such as the chat UI and the agent brain.
 *
 * The contract deliberately does not carry a system prompt: role instructions travel per turn
 * through `AgentService.send(systemPrompt = ...)`, never through provider configuration.
 */
data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val endpointMode: ModelEndpointMode = ModelEndpointMode.CHAT_COMPLETIONS,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val models: List<ModelDescriptor> = emptyList(),
    val selectedModelId: String? = null,
)

/** Outcome of [ModelProviderService.testConnection], ready to show in the provider page. */
data class ModelTestResult(
    val success: Boolean,
    val message: String,
)

/**
 * Configuration surface of the model plugin, published into `ServiceDirectory` for the settings UI.
 *
 * Storage methods are synchronous (small local file) and the network probes are `suspend`, so a UI
 * calls the latter from a coroutine. Nothing here touches a conversation: this service configures
 * providers, while [ModelService] executes completions.
 */
interface ModelProviderService {
    /** Every provider: built-in catalog entries first (in catalog order), then custom ones. */
    fun providers(): List<ModelProvider>

    /** Inserts or replaces one provider by [ModelProvider.id]. */
    fun saveProvider(provider: ModelProvider)

    /**
     * Removes a custom provider. A built-in id is treated like [resetProvider], because the catalog
     * entry always exists and deleting it would only resurrect the default on the next read.
     */
    fun deleteProvider(id: String)

    /** Restores a built-in provider to its catalog definition (URL/name/default models, no key). */
    fun resetProvider(id: String)

    /**
     * Fetches the provider's own model list with `GET {baseUrl}/models`, using merged headers and
     * the API key. Throws [IllegalStateException] with a readable message on HTTP/format failure.
     */
    suspend fun fetchRemoteModels(providerId: String): List<ModelDescriptor>

    /** Probes `GET {baseUrl}/models` and reports the outcome instead of throwing. */
    suspend fun testConnection(providerId: String): ModelTestResult

    companion object {
        val Key = ServiceKey<ModelProviderService>("agent/model-providers")
    }
}
