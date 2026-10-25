package ev.buan.plugins.eta.ui

import ev.buan.plugins.agent.api.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation rules the chat page leans on: stable ids, delete/regenerate list edits, and
 * how a turn's events collapse into tool steps (the numbers the caption prints).
 */
class ChatConversationTest {

    @Test
    fun `messages carry distinct stable ids`() {
        val user = userMessage("你好")
        val reply = assistantMessage("你好，有什么可以帮你？")
        val placeholder = assistantMessage("（未接模型）我收到了：你好")

        assertEquals(listOf(true, false, false), listOf(user.fromUser, reply.fromUser, placeholder.fromUser))
        assertNotEquals(user.id, reply.id)
        assertNotEquals(reply.id, placeholder.id)
        // Same instance keeps its id: the list key must not change between frames.
        assertEquals(user.id, user.copy(text = "改写").id)
    }

    @Test
    fun `assistant message keeps the turn events`() {
        val events = listOf(call("echo", """{"text":"hi"}""", id = "c1"))
        val message = assistantMessage("完成", events = events)

        assertEquals(events, message.events)
        assertTrue(userMessage("你好").events.isEmpty())
    }

    @Test
    fun `delete removes exactly the message with that id`() {
        val first = userMessage("第一条")
        val second = userMessage("第二条")
        val reply = assistantMessage("回答")

        assertEquals(listOf(first, second), withoutMessage(listOf(first, reply, second), reply.id))
        assertEquals(listOf(first, reply), withoutMessage(listOf(first, reply, second), second.id))
        // Unknown id is a no-op, not a wipe.
        assertEquals(listOf(first, reply), withoutMessage(listOf(first, reply), "missing"))
    }

    @Test
    fun `regenerate reuses the user turn instead of appending a new one`() {
        val question = userMessage("分析屏幕")
        val oldReply = assistantMessage("旧回答")
        val followUp = userMessage("继续")

        val plan = regenerationOf(listOf(question, oldReply, followUp), oldReply.id)

        // The user bubble survives - only the reply and everything after it are cut.
        assertEquals(listOf(question), plan?.messages)
        assertEquals("分析屏幕", plan?.prompt)
        assertEquals(1, plan?.messages?.count { it.fromUser })
    }

    @Test
    fun `regenerate refuses ids it cannot replay`() {
        val question = userMessage("问题")
        val reply = assistantMessage("回答")
        val messages = listOf(question, reply)

        assertNull(regenerationOf(messages, question.id))
        assertNull(regenerationOf(messages, "missing"))
        // An assistant message with no user turn before it has nothing to replay.
        assertNull(regenerationOf(listOf(reply), reply.id))
    }

    @Test
    fun `a turn without tool events has no steps`() {
        assertTrue(toolStepsOf(emptyList()).isEmpty)
        assertTrue(toolStepsOf(listOf(AgentEvent(kind = AgentEvent.Kind.TEXT, text = "纯文本"))).isEmpty)
        assertTrue(
            toolStepsOf(listOf(AgentEvent(kind = AgentEvent.Kind.ERROR, text = "模型调用失败"))).isEmpty,
        )
    }

    @Test
    fun `call and result are paired into one step`() {
        val steps = toolStepsOf(
            listOf(
                call("read_file", """{"path":"a.txt"}""", id = "c1"),
                result("read_file", "文件内容", id = "c1"),
            ),
        )

        assertFalse(steps.isEmpty)
        assertEquals(1, steps.thinkingCount)
        assertEquals(1, steps.toolCount)
        assertEquals(1, steps.steps.size)
        assertEquals("read_file", steps.steps.single().toolName)
        assertEquals("""{"path":"a.txt"}""", steps.steps.single().arguments)
        assertEquals("文件内容", steps.steps.single().result)
        assertEquals("思考 1 次 · 调用 1 个工具", steps.label)
    }

    @Test
    fun `parallel calls in one round count as one thought and several tools`() {
        val steps = toolStepsOf(
            listOf(
                call("read_file", """{"path":"a"}""", id = "c1"),
                call("list_dir", """{"path":"/"}""", id = "c2"),
                result("read_file", "A", id = "c1"),
                result("list_dir", "B", id = "c2"),
            ),
        )

        assertEquals(1, steps.thinkingCount)
        assertEquals(2, steps.toolCount)
        assertEquals(2, steps.steps.size)
        assertEquals("思考 1 次 · 调用 2 个工具", steps.label)
    }

    @Test
    fun `sequential rounds count as separate thoughts and repeated tools count once`() {
        val steps = toolStepsOf(
            listOf(
                call("read_file", """{"path":"a"}""", id = "c1"),
                result("read_file", "A", id = "c1"),
                call("read_file", """{"path":"b"}""", id = "c2"),
                result("read_file", "B", id = "c2"),
            ),
        )

        assertEquals(2, steps.thinkingCount)
        assertEquals(1, steps.toolCount)
        assertEquals(2, steps.steps.size)
        assertEquals("思考 2 次 · 调用 1 个工具", steps.label)
    }

    @Test
    fun `a result without a call still shows up as a step`() {
        val steps = toolStepsOf(listOf(result("unknown_tool", "没有名为 unknown_tool 的工具")))

        assertEquals(1, steps.steps.size)
        assertEquals("unknown_tool", steps.steps.single().toolName)
        assertEquals("", steps.steps.single().arguments)
        assertEquals("没有名为 unknown_tool 的工具", steps.steps.single().result)
        assertEquals(0, steps.thinkingCount)
    }

    @Test
    fun `steps keep the full text so the panel can expand beyond the preview`() {
        val longArguments = """{"path":""" + "\"x\"".repeat(200) + "}"
        val longResult = "结".repeat(500)
        val steps = toolStepsOf(
            listOf(
                call("read_file", longArguments, id = "c1"),
                result("read_file", longResult, id = "c1"),
            ),
        )

        val step = steps.steps.single()
        assertEquals(longArguments, step.arguments)
        assertEquals(longResult, step.result)
    }

    @Test
    fun `preview caps long text with an ellipsis and leaves short text alone`() {
        assertEquals("短文本", preview("短文本"))
        assertEquals("", preview(null))

        val long = "结".repeat(500)
        val capped = preview(long)
        assertEquals(TOOL_TEXT_LIMIT, capped.length)
        assertTrue(capped.endsWith("…"))

        // Whitespace is trimmed before measuring, and a custom limit is respected.
        assertEquals("abc", preview("  abc  "))
        assertEquals("ab…", preview("abcdef", maxChars = 3))
    }
}

private fun call(name: String, argumentsJson: String, id: String?) = AgentEvent(
    kind = AgentEvent.Kind.TOOL_CALL,
    toolName = name,
    toolCallId = id,
    argumentsJson = argumentsJson,
)

private fun result(name: String, text: String, id: String? = null) = AgentEvent(
    kind = AgentEvent.Kind.TOOL_RESULT,
    text = text,
    toolName = name,
    toolCallId = id,
)
