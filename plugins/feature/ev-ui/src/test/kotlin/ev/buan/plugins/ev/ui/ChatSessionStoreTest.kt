package ev.buan.plugins.ev.ui

import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageQuotaExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins what the chat page does when storage misbehaves: a damaged file must not stop the page from
 * opening, and a failed write must not take the host down with it.
 */
class ChatSessionStoreTest {

    @Test
    fun `a missing file loads as an empty history`() {
        val store = ChatSessionStore(FakeStorage(), log = { _, _ -> })

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `saved sessions load back`() {
        val storage = FakeStorage()
        val store = ChatSessionStore(storage, log = { _, _ -> })
        val session = ChatSession("s1", "标题", listOf(userMessage("你好")), 7L)

        store.save(listOf(session))

        // Replaced through the atomic path, under the agreed name.
        assertEquals(listOf(ChatSessionDocuments.PATH), storage.atomicWrites)
        assertEquals(listOf(session), store.load())
    }

    @Test
    fun `saving an empty history is a valid snapshot`() {
        val store = ChatSessionStore(FakeStorage(), log = { _, _ -> })

        store.save(emptyList())

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a corrupt file loads as empty and is reported`() {
        val logged = mutableListOf<String>()
        val storage = FakeStorage(mapOf(ChatSessionDocuments.PATH to "{ 损坏".encodeToByteArray()))
        val store = ChatSessionStore(storage) { message, _ -> logged += message }

        assertTrue(store.load().isEmpty())
        assertTrue(logged.single().contains(ChatSessionDocuments.PATH))
    }

    @Test
    fun `a failing read loads as empty and is reported`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeStorage(readFailure = IOException("磁盘错误"))
        val store = ChatSessionStore(storage) { message, error -> logged += message to error }

        assertTrue(store.load().isEmpty())
        // The cause is what makes the log line diagnosable, not just the fact that something failed.
        assertTrue(logged.single().first.contains(ChatSessionDocuments.PATH))
        assertTrue(logged.single().second?.message == "磁盘错误")
    }

    @Test
    fun `a failing write is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeStorage(writeFailure = IOException("磁盘已满"))
        val store = ChatSessionStore(storage) { message, error -> logged += message to error }

        store.save(listOf(ChatSession("s1", "标题", emptyList(), 1L)))

        assertTrue(logged.single().first.contains(ChatSessionDocuments.PATH))
        assertTrue(logged.single().second?.message == "磁盘已满")
    }

    @Test
    fun `a full quota is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeStorage(writeFailure = PluginStorageQuotaExceededException("超出配额"))
        val store = ChatSessionStore(storage) { message, error -> logged += message to error }

        store.save(listOf(ChatSession("s1", "标题", emptyList(), 1L)))

        assertTrue(logged.single().first.contains(ChatSessionDocuments.PATH))
        assertTrue(logged.single().second is PluginStorageQuotaExceededException)
    }

    @Test
    fun `a failed snapshot leaves the previous history readable`() {
        val storage = FakeStorage()
        val store = ChatSessionStore(storage, log = { _, _ -> })
        val history = listOf(ChatSession("s1", "标题", listOf(userMessage("保留")), 1L))
        store.save(history)

        storage.writeFailure = IOException("磁盘已满")
        store.save(listOf(ChatSession("s2", "新的", emptyList(), 2L)))

        assertEquals(history, store.load())
    }

    @Test
    fun `storage paths stay relative to the plugin area`() {
        val storage = FakeStorage()
        val store = ChatSessionStore(storage, log = { _, _ -> })

        store.save(listOf(ChatSession("s1", "标题", emptyList(), 1L)))

        val path = storage.atomicWrites.single()
        assertNotNull(path)
        assertTrue(path, !path.startsWith("/") && !path.contains(".."))
    }
}

/** Records what the store wrote and can be told to fail the way a real host might. */
private class FakeStorage(
    files: Map<String, ByteArray> = emptyMap(),
    var readFailure: Exception? = null,
    var writeFailure: Exception? = null,
) : PluginStorage {
    private val contents = files.toMutableMap()
    val atomicWrites = mutableListOf<String>()

    override fun read(path: String): ByteArray? {
        readFailure?.let { throw it }
        return contents[path]
    }

    override fun write(path: String, bytes: ByteArray) {
        writeFailure?.let { throw it }
        contents[path] = bytes
    }

    override fun atomicWrite(path: String, bytes: ByteArray) {
        writeFailure?.let { throw it }
        atomicWrites += path
        contents[path] = bytes
    }

    override fun delete(path: String): Boolean = contents.remove(path) != null

    override fun list(path: String): List<String> = contents.keys.sorted()
}
