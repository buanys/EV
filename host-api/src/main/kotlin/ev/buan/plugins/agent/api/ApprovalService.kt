package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/** The human's answer to one dangerous-tool request. */
enum class ApprovalDecision {
    /** Run this call; ask again next time. */
    ALLOW_ONCE,

    /** Run this call and every later call of the same tool until the UI restarts. */
    ALLOW_SESSION,

    /** Do not run; the refusal text goes back to the model as the tool result. */
    DENY,
}

/**
 * The approval gate between the brain and a dangerous tool.
 *
 * Published by whichever plugin owns the human - today that is the chat UI (ev-ui). The brain
 * looks this up before running any [ToolSpec.dangerous] call and suspends on [request] until the
 * human decides. Fail-closed by design: a dangerous tool with no gate published does not run, and
 * the refusal becomes result text for the model, so the turn degrades instead of dying.
 *
 * "Session" scope is the publisher's business: the gate decides what ALLOW_SESSION remembers and
 * for how long. The brain asks every time; a gate that already allowed this tool answers
 * immediately without showing anything.
 */
interface ApprovalService {
    /**
     * Asks the human whether [toolName] may run with [argumentsJson]. Suspends until decided -
     * the calling tool loop simply waits, so a phone sitting in a pocket blocks the call, it never
     * silently approves it.
     */
    suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision

    companion object {
        val Key = ServiceKey<ApprovalService>("agent/approval")
    }
}
