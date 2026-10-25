package ev.buan.plugins.agent.api

/**
 * Identifies one model a [ModelService] can serve.
 *
 * Both fields are opaque to consumers and to the host: [provider] is the id the model plugin uses
 * to route the call (with multiple configured providers it is the provider's id, e.g. `deepseek`),
 * and [model] is the wire name that provider expects. Keeping them as strings is what keeps this
 * contract free of vendor types.
 */
data class ModelRef(
    val provider: String,
    val model: String,
)
