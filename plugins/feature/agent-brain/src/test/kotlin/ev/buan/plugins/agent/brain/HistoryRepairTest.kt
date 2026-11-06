package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃恢复的那一半：被 kill 在工具执行中时，历史停在 assistant(tool_calls) 上，
 * 下一轮会被服务端以 400 拒绝。这组用例锁住"补成合法形状"的三种情形。
 */
class HistoryRepairTest {

    private fun assistant(vararg calls: AgentToolCall) =
        AgentMessage(role = AgentRole.ASSISTANT, content = "", toolCalls = calls.toList())

    private fun call(id: String) = AgentToolCall(id = id, name = "run_shell", argumentsJson = "{}")

    private fun tool(id: String) =
        AgentMessage(role = AgentRole.TOOL, content = "done", name = "run_shell", toolCallId = id)

    @Test
    fun `a finished conversation is left alone`() {
        val messages = listOf(
            AgentMessage(role = AgentRole.USER, content = "hi"),
            assistant(call("a")),
            tool("a"),
            AgentMessage(role = AgentRole.ASSISTANT, content = "ok"),
        )

        assertEquals(messages, HistoryRepair.closeDanglingToolCalls(messages))
    }

    @Test
    fun `a crash right after the tool call gets a synthetic result`() {
        val messages = listOf(
            AgentMessage(role = AgentRole.USER, content = "跑一下"),
            assistant(call("a")),
        )

        val repaired = HistoryRepair.closeDanglingToolCalls(messages)

        assertEquals(3, repaired.size)
        assertEquals(AgentRole.TOOL, repaired.last().role)
        assertEquals("a", repaired.last().toolCallId)
        assertEquals("run_shell", repaired.last().name)
        assertTrue(repaired.last().content.contains("没有完成"))
    }

    @Test
    fun `only the unanswered calls are filled when a sibling already returned`() {
        val messages = listOf(
            AgentMessage(role = AgentRole.USER, content = "两个都想跑"),
            assistant(call("a"), call("b")),
            tool("a"),
        )

        val repaired = HistoryRepair.closeDanglingToolCalls(messages)

        assertEquals(4, repaired.size)
        assertEquals("b", repaired.last().toolCallId)
    }

    @Test
    fun `a dangling call in the middle is closed before the next assistant turn`() {
        // 一次 400 之后，错误消息会被当作 assistant 回复写进历史，
        // 悬挂的 tool_call 就不在末尾了——这条锁住"必须扫全程"。
        val messages = listOf(
            AgentMessage(role = AgentRole.USER, content = "跑一下"),
            assistant(call("a")),
            AgentMessage(role = AgentRole.ASSISTANT, content = "模型调用失败：HTTP 400"),
        )

        val repaired = HistoryRepair.closeDanglingToolCalls(messages)

        assertEquals(4, repaired.size)
        assertEquals(AgentRole.TOOL, repaired[2].role)
        assertEquals("a", repaired[2].toolCallId)
        assertEquals("模型调用失败：HTTP 400", repaired.last().content)
    }

    @Test
    fun `an empty history stays empty`() {
        assertEquals(emptyList<AgentMessage>(), HistoryRepair.closeDanglingToolCalls(emptyList()))
    }

    @Test
    fun `a history ending in a user message is untouched`() {
        val messages = listOf(AgentMessage(role = AgentRole.USER, content = "hi"))
        assertEquals(messages, HistoryRepair.closeDanglingToolCalls(messages))
    }
}
