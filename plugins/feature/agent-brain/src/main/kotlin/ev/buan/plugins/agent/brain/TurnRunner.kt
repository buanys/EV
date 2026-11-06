package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec

/** What one [turnOnce] wants [turn] to do next. */
internal sealed interface TurnStep {
    /** The round went through: inputs are appended, run another one. */
    data object Continue : TurnStep

    /** A termination condition was hit; [stop] says which one and what it counted. */
    data class Stop(val stop: AgentStop) : TurnStep

    /**
     * The model call failed. The error [AgentReply] is already emitted, so the turn must end
     * right here - no summary completion, no TurnEnded event.
     */
    data object Abort : TurnStep
}

/**
 * The mutable state one turn carries across its rounds.
 *
 * A holder instead of a dozen parameters because most of these are *written back* by
 * [turnOnce] - rounds, tool count, approval time, the repeat streak. Kotlin passes Int/Long by
 * value, so as parameters they would each need a reference wrapper; one object is honest about
 * what they are. Its lifetime is exactly one [turn]: it is created there and never stored
 * anywhere, so no two sessions can share one.
 */
internal class TurnState(
    /** Where history is read from and messages are appended; null degrades to single-turn. */
    val memory: MemoryService?,
    /** The event log, the source of truth history is rebuilt from. */
    val sessionLog: SessionLog?,
    val sessionId: String,
    /** The tool registry, and the specs sent with every request. */
    val tools: ToolRegistry?,
    val specs: List<ToolSpec>,
    /** The approval gate and the policy that can pre-answer it. */
    val approval: ApprovalService?,
    val policy: ApprovalPolicy?,
) {
    /** The full request: system message?, stored history, user message, this turn's rounds. */
    val inputs = mutableListOf<AgentMessage>()

    /** Everything this turn produced, handed back as [AgentReply.events] verbatim. */
    val events = mutableListOf<AgentEvent>()

    /** One usage per model call, aggregated at the end. */
    val usages = mutableListOf<AgentUsage?>()

    /** Tool fingerprint -> the result it produced last time; drives the repeat guard. */
    val seenResults = mutableMapOf<String, String>()

    /** Rounds started so far. Read by the budget, by events, and by every [AgentStop]. */
    var rounds = 0

    /** Tools actually invoked so far; skipped calls do not count. */
    var toolCalls = 0

    /**
     * Wall clock spent waiting for a human, subtracted from the turn's deadline: time the user
     * spends deciding is not the agent's time, so a phone in a pocket must not eat the budget.
     */
    var approvalNanos = 0L

    /** The last answer seen, so a FINISHED turn can return it without a second call. */
    var lastReply: AgentReply? = null

    /** Normalized text of the previous tool-calling round, for the no-progress guard. */
    var previousNormalized: String? = null

    /** Consecutive identical call + identical result pairs within this turn. */
    var repeatStreak = 0
}
