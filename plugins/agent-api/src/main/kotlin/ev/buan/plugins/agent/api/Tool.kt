package ev.buan.plugins.agent.api

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
}
