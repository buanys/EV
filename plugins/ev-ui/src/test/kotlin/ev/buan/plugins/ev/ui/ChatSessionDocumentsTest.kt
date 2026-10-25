package ev.buan.plugins.ev.ui

import ev.buan.plugins.agent.api.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored chat document: what survives a restart, and what a damaged file degrades to.
 *
 * Runs against a real `org.json` (the plugin uses the platform copy at runtime, which is never
 * packaged) without a host or a [org.cordis.api.PluginStorage].
 */
class ChatSessionDocumentsTest {

    @Test
    fun `sessions survive a round trip`() {
        val sessions = listOf(
            ChatSession(
                id = "ev.buan.plugins.ev.ui/session-1",
                title = "分析屏幕",
                messages = listOf(
                    userMessage("分析一下当前屏幕"),
                    assistantMessage(
                        "屏幕上是设置页。",
                        events = listOf(
                            AgentEvent(
                                kind = AgentEvent.Kind.TOOL_CALL,
                                text = "调用工具",
                                toolName = "screen_read",
                                toolCallId = "call-1",
                                argumentsJson = """{"detail":"full"}""",
                            ),
                            AgentEvent(
                                kind = AgentEvent.Kind.TOOL_RESULT,
                                text = "设置页",
                                toolName = "screen_read",
                                toolCallId = "call-1",
                            ),
                        ),
                    ),
                ),
                updatedAt = 1_760_000_000_000L,
            ),
            ChatSession(
                id = "ev.buan.plugins.ev.ui/session-2",
                title = DEFAULT_SESSION_TITLE,
                messages = emptyList(),
                updatedAt = 1_760_000_500_000L,
            ),
        )

        val restored = ChatSessionDocuments.decode(ChatSessionDocuments.encode(sessions))

        assertEquals(sessions, restored)
    }

    @Test
    fun `message ids, text, side and events all survive`() {
        val message = assistantMessage("答", events = listOf(AgentEvent(kind = AgentEvent.Kind.TEXT, text = "思考")))
        val session = ChatSession("s1", "题", listOf(userMessage("问"), message), 42L)

        val restored = ChatSessionDocuments.decode(ChatSessionDocuments.encode(listOf(session)))
            ?.single()
            ?.messages

        assertEquals(listOf("问", "答"), restored?.map { it.text })
        assertEquals(listOf(true, false), restored?.map { it.fromUser })
        // The id is the LazyColumn key, so a regenerated list needs the stored one back unchanged.
        assertEquals(listOf(session.messages[0].id, message.id), restored?.map { it.id })
        assertEquals(message.events, restored?.get(1)?.events)
    }

    @Test
    fun `an empty history is a document, not a defect`() {
        val decoded = ChatSessionDocuments.decode(ChatSessionDocuments.encode(emptyList()))

        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `unreadable bytes decode to null`() {
        assertNull(ChatSessionDocuments.decode("{ 不是 json".encodeToByteArray()))
        // Valid JSON of the wrong shape is just as unusable.
        assertNull(ChatSessionDocuments.decode("[]".encodeToByteArray()))
        assertNull(ChatSessionDocuments.decode("""{"sessions":"nope"}""".encodeToByteArray()))
        assertNull(ChatSessionDocuments.decode("".encodeToByteArray()))
    }

    @Test
    fun `entries without ids are dropped and the rest still loads`() {
        val bytes = """
            {
              "version": 1,
              "sessions": [
                {"title": "没有 id", "updatedAt": 1, "messages": []},
                {"id": "ok", "title": "留下", "updatedAt": 2, "messages": [
                  {"text": "没有 id 的消息", "fromUser": true},
                  {"id": "m1", "text": "有 id 的消息", "fromUser": true}
                ]}
              ]
            }
        """.trimIndent().encodeToByteArray()

        val sessions = ChatSessionDocuments.decode(bytes)

        assertEquals(1, sessions?.size)
        assertEquals("ok", sessions?.single()?.id)
        assertEquals(listOf("m1"), sessions?.single()?.messages?.map { it.id })
    }

    @Test
    fun `missing optional fields fall back instead of failing`() {
        val bytes = """{"sessions":[{"id":"s1","messages":[{"id":"m1","text":"嗨","fromUser":true}]}]}"""
            .encodeToByteArray()

        val session = ChatSessionDocuments.decode(bytes)?.single()

        assertEquals(DEFAULT_SESSION_TITLE, session?.title)
        assertEquals(0L, session?.updatedAt)
        assertTrue(session?.messages?.single()?.events?.isEmpty() == true)
    }

    @Test
    fun `an unknown event kind is dropped without losing the reply`() {
        val bytes = """
            {"sessions":[{"id":"s1","title":"t","updatedAt":1,"messages":[
              {"id":"m1","text":"答","fromUser":false,"events":[
                {"kind":"HOLOGRAM","text":"未来版本"},
                {"kind":"TOOL_CALL","toolName":"file_read","toolCallId":"c1","argumentsJson":"{}"}
              ]}
            ]}]}
        """.trimIndent().encodeToByteArray()

        val message = ChatSessionDocuments.decode(bytes)?.single()?.messages?.single()

        assertEquals("答", message?.text)
        assertEquals(listOf(AgentEvent.Kind.TOOL_CALL), message?.events?.map { it.kind })
        assertEquals("file_read", message?.events?.single()?.toolName)
    }

    @Test
    fun `unknown top level keys do not block a load`() {
        val bytes = """
            {"version": 9, "future": {"x": 1}, "sessions":[{"id":"s1","title":"t","updatedAt":1,"messages":[]}]}
        """.trimIndent().encodeToByteArray()

        assertEquals(listOf("s1"), ChatSessionDocuments.decode(bytes)?.map { it.id })
    }

    @Test
    fun `the file name is the agreed one`() {
        assertEquals("sessions.json", ChatSessionDocuments.PATH)
    }

    @Test
    fun `a document written before characters existed restores a session without a role`() {
        // Exactly what the old encoder produced: no characterId key anywhere.
        val bytes = """
            {"version":1,"sessions":[{"id":"s1","title":"旧的","updatedAt":7,"messages":[
              {"id":"m1","text":"嗨","fromUser":true}
            ]}]}
        """.trimIndent().encodeToByteArray()

        val session = ChatSessionDocuments.decode(bytes)?.single()

        assertNull(session?.characterId)
        assertEquals("旧的", session?.title)
    }

    @Test
    fun `a selected character survives the round trip`() {
        val session = ChatSession(
            id = "s1",
            title = "带角色",
            messages = listOf(userMessage("你好")),
            updatedAt = 11L,
            characterId = "character-1",
        )

        val restored = ChatSessionDocuments.decode(ChatSessionDocuments.encode(listOf(session)))

        assertEquals(listOf(session), restored)
        assertEquals("character-1", restored?.single()?.characterId)
    }

    @Test
    fun `a role-less session does not write the key at all`() {
        val session = ChatSession("s1", "无角色", emptyList(), 3L)

        val text = ChatSessionDocuments.encode(listOf(session)).decodeToString()

        // Keeps the document identical in shape to what builds before the character library wrote.
        assertFalse(text.contains("characterId"))
        assertNull(ChatSessionDocuments.decode(text.encodeToByteArray())?.single()?.characterId)
    }

    @Test
    fun `a document written before skills existed restores a session without one`() {
        val bytes = """
            {"version":1,"sessions":[{"id":"s1","title":"旧的","updatedAt":7,"characterId":"c1","messages":[]}]}
        """.trimIndent().encodeToByteArray()

        val session = ChatSessionDocuments.decode(bytes)?.single()

        assertEquals("c1", session?.characterId)
        assertNull(session?.skillId)
    }

    @Test
    fun `a selected skill survives the round trip with the character`() {
        val session = ChatSession(
            id = "s1",
            title = "带角色和 Skill",
            messages = listOf(userMessage("你好")),
            updatedAt = 12L,
            characterId = "character-1",
            skillId = "skill-1",
        )

        val restored = ChatSessionDocuments.decode(ChatSessionDocuments.encode(listOf(session)))

        assertEquals(listOf(session), restored)
        assertEquals("skill-1", restored?.single()?.skillId)
    }

    @Test
    fun `a session with no libraries selection writes neither key`() {
        val session = ChatSession("s1", "纯聊天", emptyList(), 4L)

        val text = ChatSessionDocuments.encode(listOf(session)).decodeToString()

        assertFalse(text.contains("characterId"))
        assertFalse(text.contains("skillId"))
    }
}
