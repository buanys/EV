package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the stored document through a real `org.json` (the plugin uses the platform copy at
 * runtime, which is never packaged) without needing a host or a [org.cordis.api.PluginStorage].
 */
class MemoryDocumentsTest {
    @Test
    fun `messages survive a round trip`() {
        val messages = listOf(
            AgentMessage(AgentRole.SYSTEM, "你是助手"),
            AgentMessage(AgentRole.USER, "你好"),
            AgentMessage(
                role = AgentRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    AgentToolCall(id = "call-1", name = "file_read", argumentsJson = """{"path":"a.txt"}"""),
                ),
            ),
            AgentMessage(AgentRole.TOOL, """{"ok":true}""", name = "file_read", toolCallId = "call-1"),
            AgentMessage(AgentRole.ASSISTANT, "在"),
        )

        assertEquals(messages, MemoryDocuments.decode(SESSION, MemoryDocuments.encode(SESSION, messages)))
    }

    @Test
    fun `a document written before tool calls existed decodes with none`() {
        val bytes = """{"session":"$SESSION","messages":[{"role":"USER","content":"旧消息"}]}"""

        assertEquals(
            listOf(AgentMessage(AgentRole.USER, "旧消息")),
            MemoryDocuments.decode(SESSION, bytes.encodeToByteArray()),
        )
    }

    @Test
    fun `an empty session decodes to an empty history`() {
        assertTrue(MemoryDocuments.decode(SESSION, MemoryDocuments.encode(SESSION, emptyList())).isEmpty())
    }

    @Test
    fun `file names are derived from the id, never taken from it`() {
        val path = MemoryDocuments.fileFor("../../etc/passwd")

        assertTrue(path, path.startsWith("memory/sessions/"))
        assertTrue(path, path.endsWith(".json"))
        assertFalse(path, path.contains(".."))
        // Deterministic, so the same session always finds its own document again.
        assertEquals(path, MemoryDocuments.fileFor("../../etc/passwd"))
        assertNotEquals(path, MemoryDocuments.fileFor("../../etc/passwd2"))
    }

    @Test
    fun `blank session ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MemoryDocuments.fileFor(" ") }
    }

    @Test
    fun `a corrupt document is reported instead of silently dropped`() {
        assertThrows(IllegalStateException::class.java) {
            MemoryDocuments.decode(SESSION, "{ not json".encodeToByteArray())
        }
    }

    @Test
    fun `a document for another session is rejected`() {
        val bytes = MemoryDocuments.encode("other", listOf(AgentMessage(AgentRole.USER, "x")))

        assertThrows(IllegalStateException::class.java) { MemoryDocuments.decode(SESSION, bytes) }
    }

    @Test
    fun `an unknown role fails instead of being dropped`() {
        val bytes = """{"session":"$SESSION","messages":[{"role":"WIZARD","content":"x"}]}"""

        assertThrows(IllegalStateException::class.java) {
            MemoryDocuments.decode(SESSION, bytes.encodeToByteArray())
        }
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
