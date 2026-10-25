package ev.buan.plugins.ev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stored shape of the character library: what survives a restart and what a damaged file
 * degrades to. Runs against a real `org.json`, like the sessions codec test.
 */
class CharacterDocumentsTest {

    @Test
    fun `characters survive a round trip`() {
        val characters = listOf(
            character(
                id = "c1",
                name = "严谨的审阅者",
                description = "先指出风险，再给结论",
                prompt = "你是严格的代码审阅者。",
                updatedAt = 1_760_000_000_000L,
            ),
            character(id = "c2", name = "翻译", description = "", prompt = "只输出译文。", updatedAt = 1L),
        )

        assertEquals(characters, CharacterDocuments.decode(CharacterDocuments.encode(characters)))
    }

    @Test
    fun `an empty library is a document, not a defect`() {
        val decoded = CharacterDocuments.decode(CharacterDocuments.encode(emptyList()))

        assertTrue(decoded != null)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `unreadable bytes decode to null`() {
        assertNull(CharacterDocuments.decode("{ 不是 json".encodeToByteArray()))
        assertNull(CharacterDocuments.decode("[]".encodeToByteArray()))
        assertNull(CharacterDocuments.decode("""{"characters":"nope"}""".encodeToByteArray()))
        assertNull(CharacterDocuments.decode("".encodeToByteArray()))
    }

    @Test
    fun `entries without an id are dropped and the rest still loads`() {
        val bytes = """
            {"version":1,"characters":[
              {"name":"没有 id","systemPrompt":"x"},
              {"id":"ok","name":"留下","description":"d","systemPrompt":"p","updatedAt":2}
            ]}
        """.trimIndent().encodeToByteArray()

        val characters = CharacterDocuments.decode(bytes)

        assertEquals(listOf("ok"), characters?.map { it.id })
        assertEquals("p", characters?.single()?.systemPrompt)
    }

    @Test
    fun `a hand-edited entry with a blank instruction still loads`() {
        // The decoder keeps what is stored; the editor is the place that demands a name and a prompt.
        val bytes = """{"characters":[{"id":"c1","name":"半成品","systemPrompt":"  ","updatedAt":3}]}"""
            .encodeToByteArray()

        val character = CharacterDocuments.decode(bytes)?.single()

        assertEquals("半成品", character?.name)
        assertEquals("", character?.systemPrompt)
        assertFalse(character?.isSaveable == true)
    }

    @Test
    fun `missing optional fields fall back instead of failing`() {
        val bytes = """{"characters":[{"id":"c1"}]}""".encodeToByteArray()

        val character = CharacterDocuments.decode(bytes)?.single()

        assertEquals("", character?.name)
        assertEquals("", character?.description)
        assertEquals("", character?.systemPrompt)
        assertEquals(0L, character?.updatedAt)
    }

    @Test
    fun `unknown keys do not block a load`() {
        val bytes = """{"version":9,"future":true,"characters":[{"id":"c1","name":"n","systemPrompt":"p"}]}"""
            .encodeToByteArray()

        assertEquals(listOf("c1"), CharacterDocuments.decode(bytes)?.map { it.id })
    }

    @Test
    fun `the file name is the agreed one`() {
        assertEquals("characters.json", CharacterDocuments.PATH)
    }

    private fun character(
        id: String,
        name: String = "名字",
        description: String = "描述",
        prompt: String = "设定",
        updatedAt: Long = 1L,
    ) = EvCharacter(
        id = id,
        name = name,
        description = description,
        systemPrompt = prompt,
        updatedAt = updatedAt,
    )
}
