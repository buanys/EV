package ev.buan.plugins.agent.log

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.SessionEvent
import kotlinx.coroutines.runBlocking
import org.cordis.api.PluginStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话流的不变量，用真存储语义（内存版）跑。
 *
 * 不测"文件长什么样"——那是 [EventLog] 的事；这里测的是投影：写进去的事实能不能
 * 原样重建出模型看到的消息，以及两个会话会不会互相污染。
 */
class SessionLogServiceTest {

    private class MemoryStorage : PluginStorage {
        private val files = HashMap<String, ByteArray>()

        override fun read(path: String): ByteArray? = files[path]

        override fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun atomicWrite(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun list(path: String): List<String> =
            files.keys.filter { it.startsWith(path) }.sorted()
    }

    private fun service() = SessionLogService(MemoryStorage())

    @Test
    fun `messages are replayed in append order`() = runBlocking {
        val log = service()
        log.append("s1", SessionEvent.Message(AgentMessage(AgentRole.USER, "你好")))
        log.append("s1", SessionEvent.Message(AgentMessage(AgentRole.ASSISTANT, "我在")))

        val messages = log.messages("s1")

        assertEquals(listOf("你好", "我在"), messages.map { it.content })
        assertEquals(AgentRole.USER, messages[0].role)
        assertEquals(AgentRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun `tool calls survive the round trip`() = runBlocking {
        val log = service()
        val call = AgentToolCall(id = "call_7", name = "screen_tap", argumentsJson = """{"x":1}""")
        log.append(
            "s1",
            SessionEvent.Message(
                AgentMessage(role = AgentRole.ASSISTANT, content = "", toolCalls = listOf(call)),
            ),
        )

        val restored = log.messages("s1").single()

        assertEquals(1, restored.toolCalls.size)
        assertEquals("call_7", restored.toolCalls[0].id)
        assertEquals("screen_tap", restored.toolCalls[0].name)
        assertEquals("""{"x":1}""", restored.toolCalls[0].argumentsJson)
    }

    @Test
    fun `turn end is an event but not a message`() = runBlocking {
        val log = service()
        log.append("s1", SessionEvent.Message(AgentMessage(AgentRole.USER, "做点事")))
        log.append("s1", SessionEvent.TurnEnded(AgentStop(AgentStop.Reason.ROUNDS, 3, 5)))

        val messages = log.messages("s1")
        val events = log.events("s1")

        assertEquals(1, messages.size)
        assertEquals(2, events.size)
        assertTrue(events[1] is SessionEvent.TurnEnded)
        assertEquals(AgentStop.Reason.ROUNDS, (events[1] as SessionEvent.TurnEnded).stop.reason)
    }

    @Test
    fun `sessions do not leak into each other`() = runBlocking {
        val log = service()
        log.append("s1", SessionEvent.Message(AgentMessage(AgentRole.USER, "一号会话")))
        log.append("s2", SessionEvent.Message(AgentMessage(AgentRole.USER, "二号会话")))

        assertEquals(listOf("一号会话"), log.messages("s1").map { it.content })
        assertEquals(listOf("二号会话"), log.messages("s2").map { it.content })
    }

    @Test
    fun `clear only empties the session it was given`() = runBlocking {
        val log = service()
        log.append("s1", SessionEvent.Message(AgentMessage(AgentRole.USER, "留着")))
        log.append("s2", SessionEvent.Message(AgentMessage(AgentRole.USER, "清掉")))

        log.clear("s2")

        assertEquals(listOf("留着"), log.messages("s1").map { it.content })
        assertTrue(log.messages("s2").isEmpty())
        assertTrue(log.events("s2").isEmpty())
    }

    @Test
    fun `an unknown session is empty rather than an error`() = runBlocking {
        val log = service()

        assertTrue(log.messages("never-written").isEmpty())
        assertTrue(log.events("never-written").isEmpty())
    }
}
