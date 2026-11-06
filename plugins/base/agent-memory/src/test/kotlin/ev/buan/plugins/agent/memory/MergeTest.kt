package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryNote
import kotlinx.coroutines.runBlocking
import org.cordis.api.UserDataStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3's write-side merge and the explicit `replaces` correction. */
class MergeTest {

    @Test
    fun `the same fact worded differently finds its twin`() {
        val store = store()
        val first = store.remember("用户偏好先给结论，不要结尾总结", kind = MemoryNote.KIND_PREFERENCE)

        val twin = store.similar("用户偏好先给结论，不要结尾总结。", MemoryNote.KIND_PREFERENCE)

        assertEquals(first.id, twin?.id)
    }

    @Test
    fun `a different topic is not a twin`() {
        val store = store()
        store.remember("用户偏好先给结论", kind = MemoryNote.KIND_PREFERENCE)

        assertNull(store.similar("编译环境在 debian chroot 里", MemoryNote.KIND_FACT))
    }

    @Test
    fun `a different kind is never merged even with the same words`() {
        val store = store()
        store.remember("用户偏好中文", kind = MemoryNote.KIND_PREFERENCE)

        assertNull(store.similar("用户偏好中文", MemoryNote.KIND_FACT))
    }

    @Test
    fun `a note does not match itself`() {
        val store = store()
        val first = store.remember("用户偏好中文", kind = MemoryNote.KIND_PREFERENCE)

        assertNull(
            store.similar("用户偏好中文", MemoryNote.KIND_PREFERENCE, excludeId = first.id),
        )
    }

    @Test
    fun `archived notes are not candidates`() {
        val store = store()
        val first = store.remember("用户偏好中文", kind = MemoryNote.KIND_PREFERENCE)
        store.archive(first.id)

        assertNull(store.similar("用户偏好中文", MemoryNote.KIND_PREFERENCE))
    }

    @Test
    fun `replaces retires the old note and carries its sources`() {
        val store = store()
        val oldId = store.addDistilled(
            drafts = listOf(
                DistillDraft(
                    title = "回答长度",
                    body = "用户喜欢长回答",
                    kind = MemoryNote.KIND_PREFERENCE,
                    keywords = emptyList(),
                    sources = listOf("l1:s:3"),
                ),
            ),
            sessionId = "s",
        )
        val old = store.notes().first()

        val new = store.remember(
            text = "用户喜欢短回答",
            kind = MemoryNote.KIND_PREFERENCE,
            title = "回答长度",
            replaces = old.id,
        )

        assertEquals(old.id, new.supersedes)
        assertEquals(listOf("l1:s:3"), new.sources)
        val retired = store.read(old.id)!!
        assertEquals(MemoryNote.STATUS_SUPERSEDED, retired.status)
        assertFalse(retired.pinned)
        assertEquals(1, oldId)
    }

    @Test
    fun `the remember tool merges a re-worded duplicate instead of adding a copy`() = runBlocking {
        val store = store()
        val tool = MemoryTools.all(store).first { it.spec.name == "remember" }

        val first = JSONObject(tool.invoke("""{"text":"用户偏好中文回答","kind":"preference"}"""))
        val second = JSONObject(tool.invoke("""{"text":"用户偏好中文回答。","kind":"preference"}"""))

        assertEquals(first.getString("id"), second.getString("id"))
        assertTrue(second.optBoolean("merged"))
        assertEquals(1, store.notes().count { it.status == MemoryNote.STATUS_ACTIVE })
    }

    @Test
    fun `the remember tool honours replaces`() = runBlocking {
        val store = store()
        val tool = MemoryTools.all(store).first { it.spec.name == "remember" }

        val old = JSONObject(tool.invoke("""{"text":"旧结论：用户喜欢长回答","kind":"preference"}"""))
        val oldId = old.getString("id")
        val replacement = JSONObject(
            tool.invoke(
                """{"text":"新结论：用户喜欢短回答","kind":"preference","replaces":"$oldId"}""",
            ),
        )

        assertEquals(oldId, replacement.getString("replaces"))
        assertEquals(MemoryNote.STATUS_SUPERSEDED, store.read(oldId)!!.status)
        assertEquals(2, store.notes().size)
    }

    private fun store(): NoteStore = NoteStore(FakeStorage())

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
}
