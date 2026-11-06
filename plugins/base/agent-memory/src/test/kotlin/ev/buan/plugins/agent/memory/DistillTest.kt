package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distillation prompt's parser and the cursor document, without a model or a host. */
class DistillTest {

    @Test
    fun `parse accepts a fenced answer and maps sources to l1 ids`() {
        val reply = """
            ```json
            {"notes":[{"title":"偏好中文","body":"用户偏好中文回答","kind":"preference","keywords":["语言"],"sources":[2,3]}]}
            ```
        """.trimIndent()

        val drafts = DistillPrompt.parse(reply, "s1", from = 1, to = 5, existingTitles = emptyList())

        assertEquals(1, drafts.size)
        assertEquals("偏好中文", drafts[0].title)
        assertEquals("preference", drafts[0].kind)
        assertEquals(listOf("l1:s1:2", "l1:s1:3"), drafts[0].sources)
    }

    @Test
    fun `a note without sources is dropped`() {
        val reply = """{"notes":[{"title":"没来源","body":"正文","kind":"fact","sources":[]}]}"""

        assertTrue(DistillPrompt.parse(reply, "s1", 1, 5, emptyList()).isEmpty())
    }

    @Test
    fun `a source outside the batch is dropped`() {
        val reply = """{"notes":[{"title":"越界","body":"正文","sources":[9]}]}"""

        assertTrue(DistillPrompt.parse(reply, "s1", 1, 5, emptyList()).isEmpty())
    }

    @Test
    fun `titles already in the notebook are skipped`() {
        val reply = """{"notes":[{"title":"编译 环境","body":"正文","sources":[1]}]}"""

        val drafts = DistillPrompt.parse(
            reply,
            "s1",
            from = 1,
            to = 5,
            existingTitles = listOf("编译环境"),
        )

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `duplicates inside one batch keep only the first`() {
        val reply = """
            {"notes":[
              {"title":"同一条","body":"第一次","sources":[1]},
              {"title":"同一条","body":"第二次","sources":[2]}
            ]}
        """.trimIndent()

        val drafts = DistillPrompt.parse(reply, "s1", 1, 5, emptyList())

        assertEquals(1, drafts.size)
        assertEquals("第一次", drafts[0].body)
    }

    @Test
    fun `an unknown kind falls back to fact`() {
        val reply = """{"notes":[{"title":"条目","body":"正文","kind":"emotion","sources":[1]}]}"""

        assertEquals("fact", DistillPrompt.parse(reply, "s1", 1, 5, emptyList()).first().kind)
    }

    @Test
    fun `prose instead of json yields nothing instead of throwing`() {
        assertTrue(DistillPrompt.parse("我觉得没什么好记的。", "s1", 1, 5, emptyList()).isEmpty())
    }

    @Test
    fun `transcript numbers messages and skips the system prompt`() {
        val messages = listOf(
            AgentMessage(AgentRole.USER, "你好"),
            AgentMessage(AgentRole.SYSTEM, "你是助手"),
            AgentMessage(AgentRole.ASSISTANT, "在"),
        )

        val text = DistillPrompt.transcript(messages, from = 1, to = 3)

        assertTrue(text.contains("[1] user: 你好"))
        assertTrue(text.contains("[3] assistant: 在"))
        assertFalse(text.contains("[2]"))
    }

    @Test
    fun `the cursor survives a round trip`() {
        val state = DistillState(
            coveredUpTo = mapOf("s1" to 12),
            lastMessageAt = mapOf("s1" to 100L),
            pending = mapOf("s1" to DistillState.Pending(from = 13, to = 30, startedAt = 200)),
        )

        assertEquals(state, DistillState.decode(state.encode()))
    }

    @Test
    fun `a corrupt cursor decodes to an empty state`() {
        assertEquals(DistillState(), DistillState.decode("{not json".encodeToByteArray()))
    }
}
