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
    /**
     * 能不能和相邻的调用并发执行。
     *
     * **默认 false，即独占**——这是 fail-closed 的一侧：一个没声明的工具会被串行执行，
     * 而不是被塞进并行池里去踩别人的状态。只有确定"无共享副作用、可重入"的工具才该打开它。
     *
     * 权重不在工具自己手里：模型看不到这个字段，它只影响执行器怎么排。
     */
    val parallelSafe: Boolean = false,
)
