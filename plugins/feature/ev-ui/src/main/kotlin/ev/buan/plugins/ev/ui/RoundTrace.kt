package ev.buan.plugins.ev.ui

import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.StepState

/**
 * One round of an agent turn as the chat screen renders it.
 *
 * The braid is deliberate: the agent reports progress as a flat event stream, and this model folds
 * it into the structure a person can read - one collapsible block per round, with the tools it ran
 * underneath. Keeping the fold a pure function means the timeline can be asserted in tests instead
 * of only by looking at the screen.
 */
internal data class RoundTrace(
    val round: Int,
    val state: RoundState = RoundState.Running,
    val steps: List<ProgressStep> = emptyList(),
) {
    /** Steps that actually ran; skipped ones only exist to keep the tool protocol valid. */
    val executed: Int get() = steps.count { it.state != StepState.Skipped }
    val failed: Int get() = steps.count { it.state == StepState.Failed }
}

internal enum class RoundState { Running, Done }

internal data class ProgressStep(
    val id: String,
    val name: String,
    val argsJson: String,
    val state: StepState,
    val startedAt: Long,
    val elapsedMs: Long? = null,
    val preview: String? = null,
)

/**
 * Folds one [AgentProgress] into the timeline.
 *
 * Unknown events fall through unchanged: a newer brain may emit event kinds this build does not
 * know, and ignoring them is always better than clearing the screen. [AgentProgress.Done] is also
 * a no-op here - the caller consumes the final reply and the stored summary, the live timeline
 * simply stops growing.
 */
internal fun reduce(rounds: List<RoundTrace>, event: AgentProgress): List<RoundTrace> = when (event) {
    is AgentProgress.RoundStarted -> rounds + RoundTrace(round = event.round)

    is AgentProgress.ToolStarted -> rounds.mapRound(event.round) { round ->
        round.copy(
            steps = round.steps + ProgressStep(
                id = event.id,
                name = event.name,
                argsJson = event.argsJson,
                state = StepState.Running,
                startedAt = event.startedAt,
            ),
        )
    }

    is AgentProgress.ToolAwaitingApproval -> rounds.mapStep(event.id) { step ->
        step.copy(state = StepState.AwaitingApproval)
    }

    is AgentProgress.ToolFinished -> rounds.mapStep(event.id) { step ->
        step.copy(
            state = event.state,
            elapsedMs = event.elapsedMs,
            preview = event.preview,
        )
    }

    is AgentProgress.RoundFinished -> rounds.mapRound(event.round) { round ->
        round.copy(state = RoundState.Done)
    }

    is AgentProgress.Finalizing -> rounds
    is AgentProgress.Done -> rounds
    else -> rounds
}

/**
 * Marks every step that is still outstanding as [StepState.Cancelled].
 *
 * Without this, cancelling a turn leaves spinners running forever - the stream just stops, so no
 * `ToolFinished` ever arrives to close them.
 */
internal fun cancelOutstanding(rounds: List<RoundTrace>): List<RoundTrace> = rounds.map { round ->
    val steps = round.steps.map { step ->
        if (step.state == StepState.Running || step.state == StepState.AwaitingApproval) {
            step.copy(state = StepState.Cancelled)
        } else {
            step
        }
    }
    round.copy(state = RoundState.Done, steps = steps)
}

private fun List<RoundTrace>.mapRound(round: Int, transform: (RoundTrace) -> RoundTrace): List<RoundTrace> =
    map { if (it.round == round) transform(it) else it }

private fun List<RoundTrace>.mapStep(id: String, transform: (ProgressStep) -> ProgressStep): List<RoundTrace> =
    map { round ->
        if (round.steps.none { it.id == id }) {
            round
        } else {
            round.copy(steps = round.steps.map { if (it.id == id) transform(it) else it })
        }
    }
