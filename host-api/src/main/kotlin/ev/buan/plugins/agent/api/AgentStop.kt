package ev.buan.plugins.agent.api

/**
 * Why an agent turn ended.
 *
 * A turn that hits a bound must say so: a model that does not know it was cut off cannot adjust,
 * and a user who is not told cannot tell "finished" from "ran out of road". [FINISHED] is the only
 * reason that needs no explanation - the model itself decided it was done.
 *
 * [rounds] and [toolCalls] are what the turn actually spent, so a UI can show "stopped after 12
 * rounds" without recomputing anything.
 */
data class AgentStop(
    val reason: Reason,
    val rounds: Int,
    val toolCalls: Int,
    val detail: String? = null,
) {
    enum class Reason {
        /** The model answered without asking for another tool. */
        FINISHED,

        /** The model-call budget ran out. */
        ROUNDS,

        /** The tool-call budget ran out. */
        TOOL_BUDGET,

        /** The wall-clock budget ran out, excluding time spent waiting for approval. */
        DEADLINE,

        /** Two consecutive turns produced the same text: the model is not making progress. */
        NO_PROGRESS,

        /** The same tool with the same arguments returned the same result twice; a third try stops. */
        REPEATED_CALLS,

        /** The turn failed - a capability or the transport threw. */
        ERROR,
    }
}
