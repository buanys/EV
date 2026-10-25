package ev.buan.plugins.eta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the character library's rules: what may be saved, how the library is ordered, and - the part
 * the chat page depends on - which prompt (if any) a session's next turn carries.
 */
class EtaCharacterTest {

    @Test
    fun `a character needs both a name and an instruction`() {
        assertTrue(character(name = "审阅者", prompt = "你是审阅者").isSaveable)
        assertFalse(character(name = " ", prompt = "你是审阅者").isSaveable)
        assertFalse(character(name = "审阅者", prompt = "   ").isSaveable)
        assertFalse(character(name = "", prompt = "").isSaveable)
    }

    @Test
    fun `a new character has an id before anything is typed`() {
        val first = newCharacter(now = 5L)
        val second = newCharacter(now = 5L)

        assertTrue(first.id.isNotBlank())
        assertTrue(first.id != second.id)
        assertEquals("", first.name)
        assertEquals("", first.systemPrompt)
        assertFalse(first.isSaveable)
    }

    @Test
    fun `saving a character puts it in front and replaces its previous copy`() {
        val kept = character(id = "a", name = "A", prompt = "PA")
        val edited = character(id = "b", name = "B2", prompt = "PB2")
        val library = listOf(edited.copy(name = "B"), kept)

        val next = library.upsert(edited)

        assertEquals(listOf("b", "a"), next.map { it.id })
        assertEquals("B2", next.first().name)
        assertEquals("PA", next.last().systemPrompt)
    }

    @Test
    fun `deleting drops only that character`() {
        val library = listOf(character(id = "a"), character(id = "b"))

        assertEquals(listOf("b"), library.without("a").map { it.id })
        assertEquals(listOf("a", "b"), library.without("missing").map { it.id })
    }

    @Test
    fun `deleting a character clears the sessions that used it`() {
        val sessions = listOf(
            session(id = "s1", characterId = "gone"),
            session(id = "s2", characterId = "kept"),
            session(id = "s3", characterId = null),
        )

        val cleared = sessions.clearingCharacter("gone")

        assertEquals(listOf(null, "kept", null), cleared.map { it.characterId })
        // Everything else about a session must survive the clean-up.
        assertEquals(sessions.map { it.id }, cleared.map { it.id })
    }

    @Test
    fun `a session without a role sends no system prompt`() {
        assertNull(characterPrompt(listOf(character(id = "c")), session(id = "s", characterId = null)))
        assertNull(characterPrompt(listOf(character(id = "c")), session = null))
    }

    @Test
    fun `a deleted character sends no system prompt`() {
        val library = listOf(character(id = "other"))

        assertNull(characterPrompt(library, session(id = "s", characterId = "gone")))
    }

    @Test
    fun `a blank instruction is treated as no prompt`() {
        val library = listOf(character(id = "c", prompt = "   "))

        assertNull(characterPrompt(library, session(id = "s", characterId = "c")))
    }

    @Test
    fun `the selected character's instruction is trimmed and sent`() {
        val library = listOf(
            character(id = "a", prompt = " 你是 A "),
            character(id = "b", prompt = "你是 B"),
        )

        assertEquals("你是 B", characterPrompt(library, session(id = "s", characterId = "b")))
    }

    private fun character(
        id: String = "c1",
        name: String = "审阅者",
        prompt: String = "你是审阅者",
    ) = EtaCharacter(
        id = id,
        name = name,
        description = "描述",
        systemPrompt = prompt,
        updatedAt = 1L,
    )

    private fun session(id: String, characterId: String?) = ChatSession(
        id = id,
        title = "会话",
        messages = emptyList(),
        updatedAt = 1L,
        characterId = characterId,
    )
}
