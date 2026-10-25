package ev.buan.plugins.eta.ui

import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageQuotaExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins what the character library does when storage misbehaves: a damaged file must not stop the
 * panel from opening, and a failed write must not take the host down with it.
 */
class CharacterStoreTest {

    @Test
    fun `a missing file loads as an empty library`() {
        val store = CharacterStore(FakeCharacterStorage(), log = { _, _ -> })

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `saved characters load back`() {
        val storage = FakeCharacterStorage()
        val store = CharacterStore(storage, log = { _, _ -> })
        val characters = listOf(character("c1"), character("c2"))

        store.save(characters)

        // Replaced through the atomic path, under the agreed name.
        assertEquals(listOf(CharacterDocuments.PATH), storage.atomicWrites)
        assertEquals(characters, store.load())
    }

    @Test
    fun `saving an empty library is a valid snapshot`() {
        val store = CharacterStore(FakeCharacterStorage(), log = { _, _ -> })

        store.save(emptyList())

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a corrupt file loads as empty and is reported`() {
        val logged = mutableListOf<String>()
        val storage = FakeCharacterStorage(mapOf(CharacterDocuments.PATH to "{ 损坏".encodeToByteArray()))
        val store = CharacterStore(storage) { message, _ -> logged += message }

        assertTrue(store.load().isEmpty())
        assertTrue(logged.single().contains(CharacterDocuments.PATH))
    }

    @Test
    fun `a failing read loads as empty and is reported`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeCharacterStorage(readFailure = IOException("磁盘错误"))
        val store = CharacterStore(storage) { message, error -> logged += message to error }

        assertTrue(store.load().isEmpty())
        assertTrue(logged.single().first.contains(CharacterDocuments.PATH))
        assertEquals("磁盘错误", logged.single().second?.message)
    }

    @Test
    fun `a failing write is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeCharacterStorage(writeFailure = IOException("磁盘已满"))
        val store = CharacterStore(storage) { message, error -> logged += message to error }

        store.save(listOf(character("c1")))

        assertTrue(logged.single().first.contains(CharacterDocuments.PATH))
        assertEquals("磁盘已满", logged.single().second?.message)
    }

    @Test
    fun `a full quota is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeCharacterStorage(writeFailure = PluginStorageQuotaExceededException("超出配额"))
        val store = CharacterStore(storage) { message, error -> logged += message to error }

        store.save(listOf(character("c1")))

        assertTrue(logged.single().second is PluginStorageQuotaExceededException)
    }

    @Test
    fun `a failed snapshot leaves the previous library readable`() {
        val storage = FakeCharacterStorage()
        val store = CharacterStore(storage, log = { _, _ -> })
        val library = listOf(character("c1"))
        store.save(library)

        storage.writeFailure = IOException("磁盘已满")
        store.save(listOf(character("c2")))

        assertEquals(library, store.load())
    }

    private fun character(id: String) = EtaCharacter(
        id = id,
        name = "角色 $id",
        description = "",
        systemPrompt = "设定 $id",
        updatedAt = 1L,
    )
}

/** Records what the store wrote and can be told to fail the way a real host might. */
private class FakeCharacterStorage(
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
