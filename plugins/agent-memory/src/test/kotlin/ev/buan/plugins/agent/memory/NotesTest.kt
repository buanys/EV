package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notebook codec, search index and L3 renderer, all without a host. */
class NotesTest {

    @Test
    fun `a note survives a round trip`() {
        val note = MemoryNote(
            id = "m_1",
            title = "偏好先给结论",
            kind = MemoryNote.KIND_PREFERENCE,
            body = "用户偏好先给结论，不要结尾总结。",
            keywords = listOf("偏好", "格式"),
            sources = listOf("l1_0001"),
            sessions = listOf("sess_a"),
            impact = 1.4,
            origin = MemoryNote.ORIGIN_DISTILLED,
            pinned = true,
            pinOrder = 3,
            createdAt = 1_000,
            updatedAt = 2_000,
        )

        val decoded = NoteDocument.decode(NoteDocument.encode(listOf(note)))

        assertEquals(listOf(note), decoded)
    }

    @Test
    fun `a note without an id or title is skipped instead of taking the file down`() {
        val bytes = """
            {"version":1,"notes":[
              {"id":"m_1","title":"保留","kind":"fact"},
              {"title":"没有 id"},
              {"id":"m_2","title":""}
            ]}
        """.trimIndent().encodeToByteArray()

        val decoded = NoteDocument.decode(bytes)

        assertEquals(listOf("m_1"), decoded.map { it.id })
    }

    @Test
    fun `a corrupt document throws instead of pretending to be empty`() {
        val error = runCatching { NoteDocument.decode("{not json".encodeToByteArray()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `chinese bigrams find a note by a phrase`() {
        val notes = listOf(
            note("m_1", "回答偏好", MemoryNote.KIND_PREFERENCE, "用户偏好简洁，先给结论"),
            note("m_2", "编译环境", MemoryNote.KIND_FACT, "编译在 debian chroot 里跑"),
        )

        val hits = NoteSearch(notes).find("用户偏好", limit = 5)

        assertEquals("m_1", hits.first().id)
        assertTrue(hits.first().score >= 3)
    }

    @Test
    fun `ascii words are indexed whole`() {
        val notes = listOf(
            note("m_1", "编译环境", MemoryNote.KIND_FACT, "编译在 debian chroot 里跑"),
            note("m_2", "模型", MemoryNote.KIND_FACT, "默认用 qwen 那个模型"),
        )

        val hits = NoteSearch(notes).find("debian", limit = 5)

        assertEquals(listOf("m_1"), hits.map { it.id })
    }

    @Test
    fun `a query with no match returns nothing`() {
        val notes = listOf(note("m_1", "偏好", MemoryNote.KIND_FACT, "用户偏好简洁"))

        assertTrue(NoteSearch(notes).find("量子计算", limit = 5).isEmpty())
    }

    @Test
    fun `archived notes stay out of recall`() {
        val notes = listOf(
            note("m_1", "旧结论", MemoryNote.KIND_FACT, "用户喜欢长回答").copy(
                status = MemoryNote.STATUS_ARCHIVED,
            ),
            note("m_2", "新结论", MemoryNote.KIND_FACT, "用户喜欢短回答"),
        )

        val hits = NoteSearch(notes).find("用户喜欢", limit = 5)

        assertEquals(listOf("m_2"), hits.map { it.id })
    }

    @Test
    fun `only pinned active notes reach the resident block`() {
        val notes = listOf(
            note("m_1", "偏好中文", MemoryNote.KIND_PREFERENCE, "用户偏好中文").copy(
                pinned = true,
                pinOrder = 2,
            ),
            note("m_2", "没常驻", MemoryNote.KIND_FACT, "这条不该出现"),
            note("m_3", "已归档", MemoryNote.KIND_FACT, "这条也不该出现").copy(
                pinned = true,
                status = MemoryNote.STATUS_ARCHIVED,
            ),
        )

        val block = Resident.render(notes)

        assertTrue(block.contains("【关于用户】"))
        assertTrue(block.contains("- 偏好中文：用户偏好中文"))
        assertFalse(block.contains("没常驻"))
        assertFalse(block.contains("已归档"))
    }

    @Test
    fun `nothing pinned renders an empty block`() {
        val notes = listOf(note("m_1", "普通", MemoryNote.KIND_FACT, "内容"))

        assertEquals("", Resident.render(notes))
    }

    @Test
    fun `the resident block is capped and says what was left out`() {
        val body = "很长的正文".repeat(60)
        val notes = (1..40).map { index ->
            note("m_$index", "条目$index", MemoryNote.KIND_FACT, body).copy(
                pinned = true,
                pinOrder = index,
            )
        }

        val block = Resident.render(notes)

        assertTrue(block.length <= Resident.BUDGET_CHARS + 40)
        assertTrue(block.contains("未展开"))
    }

    private fun note(id: String, title: String, kind: String, body: String) = MemoryNote(
        id = id,
        title = title,
        kind = kind,
        body = body,
        createdAt = 1,
        updatedAt = 1,
    )
}
