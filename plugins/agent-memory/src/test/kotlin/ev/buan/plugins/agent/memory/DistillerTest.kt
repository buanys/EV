package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.cordis.api.UserDataStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distillation loop, end to end, with a fake model and an in-memory store.
 *
 * The timing constants are not shortened for the test: the first check happens with an empty
 * cursor (`lastMessageAt = 0`), which is exactly the "user came back after the app was closed"
 * case, so no clock manipulation is needed to reach the idle branch.
 */
class DistillerTest {

    @Test
    fun `enough messages after a quiet spell produce notes and advance the cursor`() = runBlocking {
        val storage = FakeStorage()
        val service = StorageMemoryService(storage)
        val notes = NoteStore(storage)
        val model = FakeModel { NOTE_JSON }
        val distiller = distiller(storage, service, notes, model)

        repeat(25) { index ->
            service.append(SESSION, AgentMessage(AgentRole.USER, "第 $index 条"))
        }
        distiller.onMessageStored(SESSION)

        assertEquals(1, model.calls)
        assertEquals(listOf("测试条目"), notes.titles())
        val state = stateOf(storage)
        assertEquals(25, state.covered(SESSION))
        assertNull(state.pending[SESSION])
    }

    @Test
    fun `too few messages call no model and leave the cursor alone`() = runBlocking {
        val storage = FakeStorage()
        val service = StorageMemoryService(storage)
        val notes = NoteStore(storage)
        val model = FakeModel { NOTE_JSON }
        val distiller = distiller(storage, service, notes, model)

        repeat(5) { index ->
            service.append(SESSION, AgentMessage(AgentRole.USER, "第 $index 条"))
        }
        distiller.onMessageStored(SESSION)

        assertEquals(0, model.calls)
        assertEquals(0, stateOf(storage).covered(SESSION))
    }

    @Test
    fun `a model failure clears pending and keeps the cursor for a retry`() = runBlocking {
        val storage = FakeStorage()
        val service = StorageMemoryService(storage)
        val notes = NoteStore(storage)
        val model = FakeModel { error("provider down") }
        val distiller = distiller(storage, service, notes, model)

        repeat(25) { index ->
            service.append(SESSION, AgentMessage(AgentRole.USER, "第 $index 条"))
        }
        distiller.onMessageStored(SESSION)

        val state = stateOf(storage)
        assertEquals(0, state.covered(SESSION))
        assertNull(state.pending[SESSION])
        assertTrue(notes.titles().isEmpty())
    }

    @Test
    fun `an empty answer still advances the cursor so it is not retried forever`() = runBlocking {
        val storage = FakeStorage()
        val service = StorageMemoryService(storage)
        val notes = NoteStore(storage)
        val model = FakeModel { """{"notes":[]}""" }
        val distiller = distiller(storage, service, notes, model)

        repeat(25) { index ->
            service.append(SESSION, AgentMessage(AgentRole.USER, "第 $index 条"))
        }
        distiller.onMessageStored(SESSION)

        assertEquals(25, stateOf(storage).covered(SESSION))
        assertTrue(notes.titles().isEmpty())
    }

    @Test
    fun `a title the notebook already has is not written twice`() = runBlocking {
        val storage = FakeStorage()
        val service = StorageMemoryService(storage)
        val notes = NoteStore(storage)
        notes.remember("用户偏好中文", kind = "preference", title = "测试条目")
        val model = FakeModel { NOTE_JSON }
        val distiller = distiller(storage, service, notes, model)

        repeat(25) { index ->
            service.append(SESSION, AgentMessage(AgentRole.USER, "第 $index 条"))
        }
        distiller.onMessageStored(SESSION)

        // The cursor still moves: the range has been looked at, there was just nothing new to add.
        assertEquals(listOf("测试条目"), notes.titles())
        assertEquals(25, stateOf(storage).covered(SESSION))
    }

    private fun distiller(
        storage: UserDataStorage,
        service: StorageMemoryService,
        notes: NoteStore,
        model: ModelService,
    ) = Distiller(
        storage = storage,
        memory = service,
        notes = notes,
        model = { model },
        // Unconfined runs the launched batch inline, so the test needs no waiting or timeouts.
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun stateOf(storage: FakeStorage): DistillState {
        val bytes = storage.read(DistillState.PATH)
        assertNotNull(bytes)
        return DistillState.decode(bytes!!)
    }

    private class FakeModel(private val answer: () -> String) : ModelService {
        var calls = 0

        override val models = listOf(ModelRef(provider = "fake", model = "fake"))

        override suspend fun complete(
            model: ModelRef,
            messages: List<AgentMessage>,
            tools: List<ToolSpec>,
        ): AgentReply {
            calls++
            return AgentReply(AgentMessage(AgentRole.ASSISTANT, answer()))
        }
    }

    private class FakeStorage : UserDataStorage {
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

    private companion object {
        const val SESSION = "s1"
        const val NOTE_JSON =
            """{"notes":[{"title":"测试条目","body":"正文","kind":"fact","sources":[1]}]}"""
    }
}
