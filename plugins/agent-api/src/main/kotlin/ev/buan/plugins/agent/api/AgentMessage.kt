package ev.buan.plugins.agent.api

/** Who produced an [AgentMessage]. */
enum class AgentRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * One turn of a conversation, in the vendor-neutral shape the agent contracts exchange.
 *
 * Provider plugins map these onto their own wire format; nothing vendor-specific leaks into the
 * contract or into consumers.
 */
data class AgentMessage(
    val role: AgentRole,
    /** Text of the message. A tool result may also be JSON text, depending on the tool. */
    val content: String,
    /** Tool name for [AgentRole.TOOL] messages; null for the other roles. */
    val name: String? = null,
    /** Correlates an [AgentRole.TOOL] message with the [AgentEvent.Kind.TOOL_CALL] it answers. */
    val toolCallId: String? = null,
    /**
     * Tool calls an [AgentRole.ASSISTANT] message asked for; empty for every other role.
     * Keeping them on the message is what lets a conversation replay the native tool protocol:
     * the assistant's call turn travels ahead of the [AgentRole.TOOL] results that answer it.
     */
    val toolCalls: List<AgentToolCall> = emptyList(),
)
