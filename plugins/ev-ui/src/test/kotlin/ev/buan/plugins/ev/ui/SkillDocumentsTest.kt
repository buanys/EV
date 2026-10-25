package ev.buan.plugins.ev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored shape of the skills library: what survives a restart and what a damaged file
 * degrades to. Runs against a real `org.json`, like the character and session codec tests.
 */
class SkillDocumentsTest {

    @Test
    fun `skills survive a round trip`() {
        val skills = listOf(
            skill(
                id = "k1",
                name = "先列风险",
                description = "评审时先列三个风险",
                instruction = "先列出三个风险点，再给出改动建议。",
                updatedAt = 1_760_000_000_000L,
            ),
            skill(id = "k2", name = "只输出结论", description = "", instruction = "只给结论。", updatedAt = 1L),
        )

        assertEquals(skills, SkillDocuments.decode(SkillDocuments.encode(skills)))
    }

    @Test
    fun `an empty library is a document, not a defect`() {
        val decoded = SkillDocuments.decode(SkillDocuments.encode(emptyList()))

        assertTrue(decoded != null)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `unreadable bytes decode to null`() {
        assertNull(SkillDocuments.decode("{ 不是 json".encodeToByteArray()))
        assertNull(SkillDocuments.decode("[]".encodeToByteArray()))
        assertNull(SkillDocuments.decode("""{"skills":"nope"}""".encodeToByteArray()))
        assertNull(SkillDocuments.decode("".encodeToByteArray()))
    }

    @Test
    fun `entries without an id are dropped and the rest still loads`() {
        val bytes = """
            {"version":1,"skills":[
              {"name":"没有 id","instruction":"x"},
              {"id":"ok","name":"留下","description":"d","instruction":"p","updatedAt":2}
            ]}
        """.trimIndent().encodeToByteArray()

        val skills = SkillDocuments.decode(bytes)

        assertEquals(listOf("ok"), skills?.map { it.id })
        assertEquals("p", skills?.single()?.instruction)
    }

    @Test
    fun `a hand-edited entry with a blank instruction still loads`() {
        // The decoder keeps what is stored; the editor is the place that demands a name and a body.
        val bytes = """{"skills":[{"id":"k1","name":"半成品","instruction":"  ","updatedAt":3}]}"""
            .encodeToByteArray()

        val skill = SkillDocuments.decode(bytes)?.single()

        assertEquals("半成品", skill?.name)
        assertEquals("", skill?.instruction)
        assertFalse(skill?.isSaveable == true)
    }

    @Test
    fun `missing optional fields fall back instead of failing`() {
        val bytes = """{"skills":[{"id":"k1"}]}""".encodeToByteArray()

        val skill = SkillDocuments.decode(bytes)?.single()

        assertEquals("", skill?.name)
        assertEquals("", skill?.description)
        assertEquals("", skill?.instruction)
        assertEquals(0L, skill?.updatedAt)
    }

    @Test
    fun `unknown keys do not block a load`() {
        val bytes = """{"version":9,"future":true,"skills":[{"id":"k1","name":"n","instruction":"i"}]}"""
            .encodeToByteArray()

        assertEquals(listOf("k1"), SkillDocuments.decode(bytes)?.map { it.id })
    }

    @Test
    fun `the file name is the agreed one`() {
        assertEquals("skills.json", SkillDocuments.PATH)
    }

    private fun skill(
        id: String,
        name: String = "名字",
        description: String = "描述",
        instruction: String = "指令",
        updatedAt: Long = 1L,
    ) = EvSkill(
        id = id,
        name = name,
        description = description,
        instruction = instruction,
        updatedAt = updatedAt,
    )
}
