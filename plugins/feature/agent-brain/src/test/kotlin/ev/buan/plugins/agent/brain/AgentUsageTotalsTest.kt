package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure aggregation rules for the turn-level usage shown by the UI. */
class AgentUsageTotalsTest {

    @Test
    fun `no reported usage aggregates to null`() {
        assertNull(AgentUsageTotals.aggregate(emptyList()))
        assertNull(AgentUsageTotals.aggregate(listOf(null, null)))
    }

    @Test
    fun `last prompt is kept and completion and total are summed`() {
        assertEquals(
            AgentUsage(promptTokens = 30, completionTokens = 12, totalTokens = 52),
            AgentUsageTotals.aggregate(
                listOf(
                    AgentUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                    AgentUsage(promptTokens = 30, completionTokens = 7, totalTokens = 37),
                ),
            ),
        )
    }

    @Test
    fun `calls without usage are skipped`() {
        assertEquals(
            AgentUsage(promptTokens = 10, completionTokens = 4, totalTokens = 14),
            AgentUsageTotals.aggregate(listOf(null, AgentUsage(10, 4, 14), null)),
        )
    }

    @Test
    fun `unreported fields stay null instead of becoming zero`() {
        assertEquals(
            AgentUsage(promptTokens = 20, completionTokens = null, totalTokens = null),
            AgentUsageTotals.aggregate(
                listOf(AgentUsage(promptTokens = 10), AgentUsage(promptTokens = 20)),
            ),
        )
    }
}
