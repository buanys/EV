package ev.buan.plugins.agent.api

/**
 * A capability the model may ask for, in the shape of a function declaration.
 *
 * [parametersJson] is a JSON Schema object as text: this module carries no JSON library, and the
 * provider plugin embeds the text into its own request format. A tool without parameters still
 * declares an empty object schema.
 *
 * [dangerous] marks a tool that must never run on the model's word alone: the brain routes every
 * call through the published [ApprovalService] first, and refuses the call outright when no gate
 * is published (fail-closed). Mark anything that can change device state, spend money, or touch
 * data outside the owning plugin's storage.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
    val dangerous: Boolean = false,
)
