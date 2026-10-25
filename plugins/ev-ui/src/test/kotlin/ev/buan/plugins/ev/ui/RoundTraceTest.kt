package ev.buan.plugins.ev.ui

import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The event stream is folded into the timeline the chat screen shows; that fold is pure. */
class RoundTraceTest {

    @Test
    fun `a full round produces one trace with its tools in order`() {
        var rounds = emptyList<RoundTrace>()
        rounds = reduce(rounds, AgentProgress.RoundStarted(1))
        rounds = reduce(rounds, toolStarted(1, "c1", "file_read"))
        rounds = reduce(rounds, AgentProgress.ToolFinished("c1", StepState.Ok, 12, "hello"))
        rounds = reduce(rounds, toolStarted(1, "c2", "file_write"))
        rounds = reduce(rounds, AgentProgress.ToolFinished("c2", StepState.Failed, 30, "denied"))
        rounds = reduce(rounds, AgentProgress.RoundFinished(1))

        assertEquals(1, rounds.size)
        assertEquals(RoundState.Done, rounds.single().state)
        assertEquals(listOf("c1", "c2"), rounds.single().steps.map { it.id })
        assertEquals(listOf(StepState.Ok, StepState.Failed), rounds.single().steps.map { it.state })
        assertEquals(1, rounds.single().failed)
        assertEquals(2, rounds.single().executed)
    }

    @Test
    fun `awaiting approval is visible before the tool finishes`() {
        var rounds = reduce(emptyList(), AgentProgress.RoundStarted(1))
        rounds = reduce(rounds, toolStarted(1, "c1", "run_shell"))
        rounds = reduce(rounds, AgentProgress.ToolAwaitingApproval("c1"))

        assertEquals(StepState.AwaitingApproval, rounds.single().steps.single().state)

        rounds = reduce(rounds, AgentProgress.ToolFinished("c1", StepState.Ok, 5, "done"))
        assertEquals(StepState.Ok, rounds.single().steps.single().state)
    }

    @Test
    fun `skipped steps are counted apart from executed ones`() {
        var rounds = reduce(emptyList(), AgentProgress.RoundStarted(1))
        rounds = reduce(rounds, toolStarted(1, "c1", "t1"))
        rounds = reduce(rounds, AgentProgress.ToolFinished("c1", StepState.Skipped, 0, null))

        assertEquals(0, rounds.single().executed)
        assertEquals(0, rounds.single().failed)
    }

    @Test
    fun `cancelling leaves no step spinning`() {
        var rounds = reduce(emptyList(), AgentProgress.RoundStarted(1))
        rounds = reduce(rounds, toolStarted(1, "c1", "slow_tool"))
        rounds = reduce(rounds, toolStarted(1, "c2", "run_shell"))
        rounds = reduce(rounds, AgentProgress.ToolAwaitingApproval("c2"))

        rounds = cancelOutstanding(rounds)

        val states = rounds.single().steps.map { it.state }
        assertEquals(listOf(StepState.Cancelled, StepState.Cancelled), states)
        assertEquals(RoundState.Done, rounds.single().state)
        assertTrue("取消后不应残留运行中", states.none { it == StepState.Running })
    }

    @Test
    fun `events for an unknown round or step are ignored`() {
        var rounds = reduce(emptyList(), AgentProgress.RoundStarted(1))
        rounds = reduce(rounds, AgentProgress.ToolFinished("nope", StepState.Ok, 1, "x"))
        rounds = reduce(rounds, AgentProgress.RoundFinished(9))

        assertEquals(1, rounds.size)
        assertEquals(RoundState.Running, rounds.single().state)
        assertEquals(0, rounds.single().steps.size)
    }

    @Test
    fun `Done does not change the live timeline`() {
        val rounds = reduce(emptyList(), AgentProgress.RoundStarted(1))
        val after = reduce(rounds, AgentProgress.Finalizing)
        assertEquals(rounds, after)
    }

    private fun toolStarted(round: Int, id: String, name: String): AgentProgress =
        AgentProgress.ToolStarted(round, id, name, "{}", 1_000L)
}
