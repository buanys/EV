package ev.buan.plugins.ev.ui

import ev.buan.plugins.agent.api.ApprovalDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The gate is the human half of the dangerous-tool contract: these tests pin that a request
 * suspends until a decision, that a session allow short-circuits the dialog, that a cancelled
 * turn leaves no ghost prompt, and that teardown denies whatever is still waiting.
 */
class ApprovalGateTest {

    @Test
    fun `a request suspends until the human decides`() = runBlocking {
        val gate = ApprovalGate()
        val answer = async { gate.request("run_shell", """{"command":"reboot"}""") }

        // Let the requester arrive; the prompt must be visible before any decision exists.
        delay(50)
        assertEquals(false, answer.isCompleted)
        val prompt = gate.pending.single()
        assertEquals("run_shell", prompt.toolName)
        assertEquals("""{"command":"reboot"}""", prompt.argumentsJson)

        gate.decide(prompt.id, ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, answer.await())
        assertTrue(gate.pending.isEmpty())
    }

    @Test
    fun `queued requests surface one at a time in arrival order`() = runBlocking {
        val gate = ApprovalGate()
        val first = async { gate.request("run_shell", "{}") }
        val second = async { gate.request("reboot_device", "{}") }
        delay(50)

        assertEquals(listOf("run_shell", "reboot_device"), gate.pending.map { it.toolName })

        gate.decide(gate.pending.first().id, ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, first.await())
        assertEquals(listOf("reboot_device"), gate.pending.map { it.toolName })

        gate.decide(gate.pending.first().id, ApprovalDecision.DENY)
        assertEquals(ApprovalDecision.DENY, second.await())
        assertTrue(gate.pending.isEmpty())
    }

    @Test
    fun `a session allow answers later calls of the same tool without a prompt`() = runBlocking<Unit> {
        val gate = ApprovalGate()
        val first = async { gate.request("run_shell", "{}") }
        delay(50)
        gate.decide(gate.pending.single().id, ApprovalDecision.ALLOW_SESSION)
        assertEquals(ApprovalDecision.ALLOW_SESSION, first.await())

        // No dialog, no suspension: the answer is immediate from here on.
        assertEquals(ApprovalDecision.ALLOW_ONCE, gate.request("run_shell", """{"command":"reboot"}"""))
        assertTrue(gate.pending.isEmpty())
        // Another tool still asks.
        val other = async { gate.request("reboot_device", "{}") }
        delay(50)
        assertEquals(listOf("reboot_device"), gate.pending.map { it.toolName })
        gate.denyAll()
        other.await()
    }

    @Test
    fun `a denied call completes with DENY and a stale id decides nothing`() = runBlocking {
        val gate = ApprovalGate()
        val answer = async { gate.request("run_shell", "{}") }
        delay(50)
        val prompt = gate.pending.single()

        gate.decide("not-a-real-id", ApprovalDecision.ALLOW_ONCE)
        assertEquals(false, answer.isCompleted)
        assertEquals(1, gate.pending.size)

        gate.decide(prompt.id, ApprovalDecision.DENY)
        assertEquals(ApprovalDecision.DENY, answer.await())
        assertTrue(gate.pending.isEmpty())
    }

    @Test
    fun `a cancelled request removes its prompt so the dialog never asks about a dead call`() = runBlocking {
        val gate = ApprovalGate()
        val answer = async { gate.request("run_shell", "{}") }
        delay(50)
        assertEquals(1, gate.pending.size)

        answer.cancel()
        delay(50)
        assertTrue(gate.pending.isEmpty())
        try {
            answer.await()
            fail("cancelled request must not complete normally")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `denyAll answers everything still waiting, fail-closed`() = runBlocking {
        val gate = ApprovalGate()
        val one = async { gate.request("run_shell", "{}") }
        val two = async { gate.request("reboot_device", "{}") }
        delay(50)

        gate.denyAll()
        assertEquals(ApprovalDecision.DENY, one.await())
        assertEquals(ApprovalDecision.DENY, two.await())
        assertTrue(gate.pending.isEmpty())
    }
}
