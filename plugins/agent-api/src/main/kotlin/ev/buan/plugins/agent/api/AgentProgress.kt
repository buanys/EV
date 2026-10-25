package ev.buan.plugins.agent.api

/**
 * One step of a streaming agent turn, pushed to the UI while the turn is still running.
 *
 * Before this existed the UI saw nothing until the whole loop finished: it showed a three-dot
 * animation unrelated to what the agent was doing, then appended every tool card at the end, so the
 * real order of events (fail, change approach, succeed) was only visible after the fact.
 *
 * The events are intentionally coarse: one per round and one per tool. A ticker for elapsed time is
 * a UI concern - the loop does not send heartbeats.
 */
sealed interface AgentProgress {
    /** A model call just returned and a new round begins. */
    data class RoundStarted(val round: Int) : AgentProgress

    /** A tool is about to run. [argsJson] is for display; the model-facing copy travels as a message. */
    data class ToolStarted(
        val round: Int,
        val id: String,
        val name: String,
        val argsJson: String,
        val startedAt: Long,
    ) : AgentProgress

    /** A dangerous tool is waiting for the human; the UI should say so rather than look stuck. */
    data class ToolAwaitingApproval(val id: String) : AgentProgress

    /**
     * A tool finished. [preview] is a short excerpt (at most 300 chars) for display only - the full
     * result goes back to the model through the normal TOOL message and never through this event.
     */
    data class ToolFinished(
        val id: String,
        val state: StepState,
        val elapsedMs: Long,
        val preview: String?,
    ) : AgentProgress

    /** Every tool of [round] is done; the next step is another model call. */
    data class RoundFinished(val round: Int) : AgentProgress

    /** The budget ran out and a tool-free summary call is running. */
    data object Finalizing : AgentProgress

    /** The turn is over. Always the last event of a stream. */
    data class Done(val reply: AgentReply) : AgentProgress
}

/** Lifecycle of one tool call inside a round. */
enum class StepState {
    /** Started, no result yet. */
    Running,

    /** Blocked on the human approval gate. */
    AwaitingApproval,

    /** Returned normally. */
    Ok,

    /** Threw, timed out, or was refused. */
    Failed,

    /** Never ran because the budget was already spent. */
    Skipped,

    /** The user stopped the turn while this step was outstanding. */
    Cancelled,
}
