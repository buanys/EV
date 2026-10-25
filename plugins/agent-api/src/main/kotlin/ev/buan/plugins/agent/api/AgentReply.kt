package ev.buan.plugins.agent.api

/**
 * Result of one model or agent turn.
 *
 * [message] is the assistant message; [events] keeps the intermediate steps in order so an agent
 * can execute [AgentEvent.Kind.TOOL_CALL] requests and a UI can render progress. [model] is the
 * model that produced the turn, when the caller knows it. [usage] is the token accounting the
 * provider reported for the completion(s) behind this turn; null when nothing was reported.
 */
data class AgentReply(
    val message: AgentMessage,
    val model: ModelRef? = null,
    val events: List<AgentEvent> = emptyList(),
    val usage: AgentUsage? = null,
)
