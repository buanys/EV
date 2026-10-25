package ev.buan.plugins.ev.ui

import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageQuotaExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins what the skills library does when storage misbehaves: a damaged file must not stop the panel
 * from opening, and a failed write must not take the host down with it.
 */
class SkillStoreTest {

    @Test
    fun `a missing file loads as an empty library`() {
        val store = SkillStore(FakeSkillStorage(), log = { _, _ -> })

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `saved skills load back`() {
        val storage = FakeSkillStorage()
        val store = SkillStore(storage, log = { _, _ -> })
        val skills = listOf(skill("k1"), skill("k2"))

        store.save(skills)

        // Replaced through the atomic path, under the agreed name.
        assertEquals(listOf(SkillDocuments.PATH), storage.atomicWrites)
        assertEquals(skills, store.load())
    }

    @Test
    fun `saving an empty library is a valid snapshot`() {
        val store = SkillStore(FakeSkillStorage(), log = { _, _ -> })

        store.save(emptyList())

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a corrupt file loads as empty and is reported`() {
        val logged = mutableListOf<String>()
        val storage = FakeSkillStorage(mapOf(SkillDocuments.PATH to "{ 损坏".encodeToByteArray()))
        val store = SkillStore(storage) { message, _ -> logged += message }

        assertTrue(store.load().isEmpty())
        assertTrue(logged.single().contains(SkillDocuments.PATH))
    }

    @Test
    fun `a failing read loads as empty and is reported`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeSkillStorage(readFailure = IOException("磁盘错误"))
        val store = SkillStore(storage) { message, error -> logged += message to error }

        assertTrue(store.load().isEmpty())
        assertTrue(logged.single().first.contains(SkillDocuments.PATH))
        assertEquals("磁盘错误", logged.single().second?.message)
    }

    @Test
    fun `a failing write is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeSkillStorage(writeFailure = IOException("磁盘已满"))
        val store = SkillStore(storage) { message, error -> logged += message to error }

        store.save(listOf(skill("k1")))

        assertTrue(logged.single().first.contains(SkillDocuments.PATH))
        assertEquals("磁盘已满", logged.single().second?.message)
    }

    @Test
    fun `a full quota is logged, not thrown`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val storage = FakeSkillStorage(writeFailure = PluginStorageQuotaExceededException("超出配额"))
        val store = SkillStore(storage) { message, error -> logged += message to error }

        store.save(listOf(skill("k1")))

        assertTrue(logged.single().second is PluginStorageQuotaExceededException)
    }

    @Test
    fun `a failed snapshot leaves the previous library readable`() {
        val storage = FakeSkillStorage()
        val store = SkillStore(storage, log = { _, _ -> })
        val library = listOf(skill("k1"))
        store.save(library)

        storage.writeFailure = IOException("磁盘已满")
        store.save(listOf(skill("k2")))

        assertEquals(library, store.load())
    }

    private fun skill(id: String) = EvSkill(
        id = id,
        name = "Skill $id",
        description = "",
        instruction = "指令 $id",
        updatedAt = 1L,
    )
}

/** Records what the store wrote and can be told to fail the way a real host might. */
private class FakeSkillStorage(
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
