package ev.buan.plugins.ev.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.cordis.api.PluginStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation rules that used to live inside an 800-line composable: which session a
 * send targets, when an edit is refused, and what "new conversation" leaves selected.
 *
 * The controller takes its state as constructor arguments, so none of this needs a device, a
 * composition or a running agent: the no-agent branch is the one under test, and it answers
 * through the same code path the real one does.
 */
class ChatSessionControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class RecordingStorage : PluginStorage {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0

        override fun read(path: String): ByteArray? = files[path]

        override fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
            writes += 1
        }

        override fun atomicWrite(path: String, bytes: ByteArray) = write(path, bytes)

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun list(path: String): List<String> = files.keys.sorted()
    }

    private fun session(id: String = "s1") =
        ChatSession(id, DEFAULT_SESSION_TITLE, emptyList(), System.currentTimeMillis())

    private fun controller(
        sessions: SnapshotStateList<ChatSession> = mutableStateListOf(session()),
        storage: RecordingStorage = RecordingStorage(),
    ) = ChatSessionController(
        sessions = sessions,
        store = ChatSessionStore(storage),
        // No agent: requestReply takes the "not connected" branch, which is synchronous enough to
        // assert on (it appends its notice after a short delay, so tests never wait for it).
        directory = null,
        skills = emptyList(),
        scope = scope,
        newSession = { session("new-${System.nanoTime()}") },
        unconnectedPrefix = "（未接模型）",
    )

    @Test
    fun `send appends the user message and titles the conversation from it`() {
        val sessions = mutableStateListOf(session())
        val chat = controller(sessions)

        chat.send("第一句话")

        val messages = sessions.single().messages
        assertEquals(1, messages.size)
        assertTrue(messages.single().fromUser)
        assertEquals("第一句话", messages.single().text)
        assertEquals("第一句话", sessions.single().title)
    }

    @Test
    fun `blank text is not a message`() {
        val sessions = mutableStateListOf(session())
        val chat = controller(sessions)

        chat.send("   ")

        assertTrue(sessions.single().messages.isEmpty())
    }

    @Test
    fun `a second send while replying is dropped`() {
        val sessions = mutableStateListOf(session())
        val chat = controller(sessions)

        chat.send("一")
        val afterFirst = sessions.single().messages.size
        chat.send("二")

        // Only the first turn made it in; the second is refused while the first is in flight.
        assertEquals(afterFirst, sessions.single().messages.size)
        assertEquals(sessions.single().id, chat.replyingSessionId)
    }

    @Test
    fun `select switches the active conversation`() {
        val first = session("a")
        val second = session("b")
        val chat = controller(mutableStateListOf(first, second))

        assertEquals("a", chat.activeSessionId)
        chat.select("b")

        assertEquals("b", chat.activeSessionId)
        assertEquals("b", chat.activeSession.id)
    }

    @Test
    fun `newChat adds a conversation, selects it and persists`() {
        val storage = RecordingStorage()
        val sessions = mutableStateListOf(session())
        val chat = controller(sessions, storage)

        chat.newChat()

        assertEquals(2, sessions.size)
        assertEquals(sessions.last().id, chat.activeSessionId)
        assertNotNull(storage.files["sessions.json"])
    }

    @Test
    fun `delete removes exactly one bubble`() {
        val sessions = mutableStateListOf(
            session().copy(
                messages = listOf(userMessage("问"), assistantMessage("答")),
            ),
        )
        val chat = controller(sessions)
        val answerId = sessions.single().messages.last().id

        chat.delete(answerId)

        assertEquals(1, sessions.single().messages.size)
        assertTrue(sessions.single().messages.single().fromUser)
    }

    @Test
    fun `edits are refused while that conversation is replying`() {
        val sessions = mutableStateListOf(
            session().copy(messages = listOf(userMessage("问"), assistantMessage("答"))),
        )
        val chat = controller(sessions)
        chat.send("再问一句")
        assertTrue(chat.isReplying)

        val before = sessions.single().messages.size
        chat.delete(sessions.single().messages.last().id)

        assertEquals(before, sessions.single().messages.size)
    }

    @Test
    fun `regenerate cuts back to the user bubble`() {
        val question = userMessage("同一个问题")
        val sessions = mutableStateListOf(
            session().copy(messages = listOf(question, assistantMessage("旧回答"))),
        )
        val chat = controller(sessions)

        chat.regenerate(sessions.single().messages.last().id)

        // The answer is dropped, the question stays, and a fresh turn was started for it.
        assertEquals(listOf(question.id), sessions.single().messages.map { it.id })
        assertTrue(chat.isReplying)
    }

    @Test
    fun `append bumps the updated timestamp and persists`() {
        val storage = RecordingStorage()
        val sessions = mutableStateListOf(session().copy(updatedAt = 1L))
        val chat = controller(sessions, storage)

        chat.append(sessions.single().id, userMessage("记一笔"))

        assertTrue(sessions.single().updatedAt > 1L)
        assertNotNull(storage.files["sessions.json"])
        assertFalse(storage.writes == 0)
    }
}
