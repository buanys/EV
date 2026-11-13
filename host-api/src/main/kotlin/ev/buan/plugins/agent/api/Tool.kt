package ev.buan.plugins.agent.api

/** What one tool call produced: text for the next turn, plus any images the model should see. */
data class ToolResult(
    val text: String,
    val images: List<AgentImage> = emptyList(),
)

/**
 * One capability an agent may invoke, published inside a [ToolRegistry].
 *
 * [invoke] receives the arguments as the JSON object text the model produced and returns the result
 * as text for the next model turn. The model only ever sees [spec]; how a tool reaches its data is
 * the owning plugin's business.
 */
interface Tool {
    val spec: ToolSpec

    /** Runs the tool with [argumentsJson]; returns the result text (plain text or JSON). */
    suspend fun invoke(argumentsJson: String): String

    /**
     * Full result, including images.
     *
     * Default: text only, so every existing tool keeps its one-method contract. A tool that
     * produces a picture (reading a screenshot) overrides this and puts it in
     * [ToolResult.images]; the brain then attaches it to the next model request.
     */
    suspend fun invokeResult(argumentsJson: String): ToolResult =
        ToolResult(text = invoke(argumentsJson))
}
