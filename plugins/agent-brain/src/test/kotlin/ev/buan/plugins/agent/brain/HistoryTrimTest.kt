package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim keeps or drops whole turns: an assistant `tool_calls` message and its tool results must
 * never be split, because one without the other is an invalid wire conversation.
 */
class HistoryTrimTest {
    @Test
    fun `history under budget is kept verbatim`() {
        val history = listOf(user("一"), assistant("答一"), user("二"), assistant("答二"))

        assertEquals(history, HistoryTrim.trim(history, maxTurns = 10, maxChars = 1000))
    }

    @Test
    fun `oldest turns are dropped first and the newest turn is always kept`() {
        val history = listOf(
            user("旧"),
            assistant("旧回答"),
            user("新"),
            assistant("新".repeat(100)),
        )

        val trimmed = HistoryTrim.trim(history, maxTurns = 10, maxChars = 50)

        // The newest turn alone exceeds the budget; dropping everything is not an option.
        assertEquals(listOf(user("新"), assistant("新".repeat(100))), trimmed)
    }

    @Test
    fun `the turn count bound drops whole turns`() {
        val history = listOf(
            user("一"), assistant("答一"),
            user("二"), assistant("答二"),
            user("三"), assistant("答三"),
        )

        val trimmed = HistoryTrim.trim(history, maxTurns = 2, maxChars = 1000)

        assertEquals(listOf(user("二"), assistant("答二"), user("三"), assistant("答三")), trimmed)
    }

    @Test
    fun `a tool turn is kept or dropped as one group`() {
        val toolTurn = listOf(
            user("读文件"),
            assistant(
                "",
                calls = listOf(AgentToolCall(id = "call_1", name = "file_read", argumentsJson = "{}")),
            ),
            AgentMessage(AgentRole.TOOL, "x".repeat(100), name = "file_read", toolCallId = "call_1"),
            assistant("读完了"),
        )
        val history = listOf(user("寒暄"), assistant("你好")) + toolTurn

        // Budget fits the tool turn (106 chars) but not the small talk too: the drop must not
        // orphan the TOOL message.
        val trimmed = HistoryTrim.trim(history, maxTurns = 10, maxChars = 108)

        assertEquals(toolTurn, trimmed)
        // A one-turn budget keeps the tool turn whole - never a fragment of it.
        val stricter = HistoryTrim.trim(history, maxTurns = 1, maxChars = 1000)
        assertEquals(toolTurn, stricter)
    }

    @Test
    fun `empty history stays empty`() {
        assertEquals(emptyList<AgentMessage>(), HistoryTrim.trim(emptyList(), maxTurns = 10, maxChars = 100))
    }

    private fun user(text: String) = AgentMessage(AgentRole.USER, text)

    private fun assistant(text: String, calls: List<AgentToolCall> = emptyList()) =
        AgentMessage(AgentRole.ASSISTANT, text, toolCalls = calls)
}

class TextCutTest {
    @Test
    fun `short text is kept as is`() {
        assertEquals("abc", truncateUtf16Safe("abc", maxChars = 8))
    }

    @Test
    fun `a plain boundary cut keeps exactly the budget`() {
        assertEquals("a".repeat(8), truncateUtf16Safe("a".repeat(20), maxChars = 8))
    }

    @Test
    fun `an emoji straddling the boundary is dropped whole, not sawn in half`() {
        // 7 chars + one emoji (a surrogate pair): cutting at 8 would leave a lone high surrogate.
        val text = "a".repeat(7) + "😀" + "tail"

        val cut = truncateUtf16Safe(text, maxChars = 8)

        assertEquals("a".repeat(7), cut)
        assertTrue(cut.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }
}
