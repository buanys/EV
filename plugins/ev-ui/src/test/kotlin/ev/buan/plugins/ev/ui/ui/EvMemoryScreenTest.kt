package ev.buan.plugins.ev.ui.ui

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the memory panel's state machine: the two "empty-looking" states stay distinguishable, a
 * failing plugin surfaces as a message instead of a crash, and clearing drops only this session's
 * agent memory.
 */
class EvMemoryScreenTest {

    @Test
    fun `no memory service is the missing state`() = runBlocking {
        assertEquals(MemoryState.Missing, readMemory({ null }, SESSION))
    }

    @Test
    fun `an empty history is the empty state, not the missing one`() = runBlocking {
        assertEquals(MemoryState.Empty, readMemory({ FakeMemory() }, SESSION))
    }

    @Test
    fun `stored turns come back oldest first`() = runBlocking {
        val memory = FakeMemory()
        memory.append(SESSION, AgentMessage(AgentRole.USER, "你好"))
        memory.append(SESSION, AgentMessage(AgentRole.ASSISTANT, "你好，有什么可以帮你？"))

        val state = readMemory({ memory }, SESSION)

        assertEquals(
            listOf("你好", "你好，有什么可以帮你？"),
            (state as MemoryState.Loaded).messages.map { it.content },
        )
    }

    @Test
    fun `only the requested session is read`() = runBlocking {
        val memory = FakeMemory()
        memory.append(SESSION, AgentMessage(AgentRole.USER, "本会话"))
        memory.append("other", AgentMessage(AgentRole.USER, "别的会话"))

        val state = readMemory({ memory }, SESSION)

        assertEquals(listOf("本会话"), (state as MemoryState.Loaded).messages.map { it.content })
    }

    @Test
    fun `a failing history is reported instead of crashing`() = runBlocking {
        val memory = FakeMemory().apply { historyFailure = IllegalStateException("文件无法解析") }

        assertEquals(MemoryState.Failed("文件无法解析"), readMemory({ memory }, SESSION))
    }

    @Test
    fun `a failing lookup is reported instead of looking uninstalled`() = runBlocking {
        val state = readMemory({ error("服务目录坏了") }, SESSION)

        assertEquals(MemoryState.Failed("服务目录坏了"), state)
    }

    @Test
    fun `clearing drops this session and re-reads to empty`() = runBlocking {
        val memory = FakeMemory()
        memory.append(SESSION, AgentMessage(AgentRole.USER, "会被清掉"))

        val state = clearMemory({ memory }, SESSION)

        assertEquals(MemoryState.Empty, state)
        assertEquals(listOf(SESSION), memory.cleared)
    }

    @Test
    fun `clearing keeps other sessions`() = runBlocking {
        val memory = FakeMemory()
        memory.append(SESSION, AgentMessage(AgentRole.USER, "本会话"))
        memory.append("other", AgentMessage(AgentRole.USER, "别的会话"))

        clearMemory({ memory }, SESSION)

        assertEquals(listOf("别的会话"), memory.history("other").map { it.content })
    }

    @Test
    fun `a failing clear is reported with its cause`() = runBlocking {
        val memory = FakeMemory().apply { clearFailure = IllegalStateException("存储只读") }

        assertEquals(MemoryState.Failed("清空失败：存储只读"), clearMemory({ memory }, SESSION))
    }

    @Test
    fun `clearing without a service reports the missing state`() = runBlocking {
        assertEquals(MemoryState.Missing, clearMemory({ null }, SESSION))
    }

    @Test
    fun `roles have readable labels`() {
        assertEquals("系统", AgentRole.SYSTEM.label())
        assertEquals("用户", AgentRole.USER.label())
        assertEquals("助手", AgentRole.ASSISTANT.label())
        assertEquals("工具", AgentRole.TOOL.label())
    }

    @Test
    fun `a tool turn shows its tool name above the content`() {
        val message = AgentMessage(AgentRole.TOOL, """{"ok":true}""", name = "file_read", toolCallId = "c1")

        assertEquals("工具 file_read\n{\"ok\":true}", memoryEntrySummary(message))
    }

    @Test
    fun `a tool turn without a name is just its content`() {
        val message = AgentMessage(AgentRole.TOOL, "结果")

        assertEquals("结果", memoryEntrySummary(message))
    }

    @Test
    fun `a blank turn stays readable`() {
        assertEquals("（空内容）", memoryEntrySummary(AgentMessage(AgentRole.USER, "  ")))
    }

    @Test
    fun `other roles never grow a tool prefix`() {
        // A name is only meaning-bearing for TOOL turns; elsewhere it must not leak into the row.
        assertEquals("你好", memoryEntrySummary(AgentMessage(AgentRole.USER, "你好", name = "ignored")))
        assertEquals("答", memoryEntrySummary(AgentMessage(AgentRole.ASSISTANT, "答", name = "ignored")))
    }

    private companion object {
        const val SESSION = "ev.buan.plugins.ev.ui/session-1"
    }
}

/** In-memory [MemoryService] that can be told to fail the way a damaged storage would. */
private class FakeMemory : MemoryService {
    private val stored = mutableMapOf<String, MutableList<AgentMessage>>()
    var historyFailure: Exception? = null
    var clearFailure: Exception? = null
    val cleared = mutableListOf<String>()

    override suspend fun history(sessionId: String): List<AgentMessage> {
        historyFailure?.let { throw it }
        return stored[sessionId].orEmpty().toList()
    }

    override suspend fun append(sessionId: String, message: AgentMessage) {
        stored.getOrPut(sessionId) { mutableListOf() } += message
    }

    override suspend fun clear(sessionId: String) {
        clearFailure?.let { throw it }
        cleared += sessionId
        stored.remove(sessionId)
    }
}
