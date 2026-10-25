package ev.buan.plugins.agent.api

/**
 * One tool call an assistant message asked for, in the vendor-neutral shape.
 *
 * Carried on [AgentMessage.toolCalls] so the next request can present the assistant's tool-call
 * turn verbatim - the OpenAI-compatible wire protocol requires an assistant `tool_calls` message
 * before any `tool` role result, and the ids must match.
 */
data class AgentToolCall(
    /** Correlates this call with the [AgentRole.TOOL] message that answers it. */
    val id: String,
    val name: String,
    /** Arguments as the model produced them: a JSON object in text form. */
    val argumentsJson: String,
)
