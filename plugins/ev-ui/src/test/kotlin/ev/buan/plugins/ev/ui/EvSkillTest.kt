package ev.buan.plugins.ev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the skills library rules and - the part the chat page depends on - how a session's character
 * and skill are folded into one system prompt.
 */
class EvSkillTest {

    @Test
    fun `a skill needs both a name and an instruction`() {
        assertTrue(skill(name = "先列风险", instruction = "先列三个风险").isSaveable)
        assertFalse(skill(name = "  ", instruction = "先列三个风险").isSaveable)
        assertFalse(skill(name = "先列风险", instruction = "  ").isSaveable)
        assertFalse(skill(name = "", instruction = "").isSaveable)
    }

    @Test
    fun `a new skill has an id before anything is typed`() {
        val first = newSkill(now = 7L)
        val second = newSkill(now = 7L)

        assertTrue(first.id.isNotBlank())
        assertTrue(first.id != second.id)
        assertEquals("", first.name)
        assertEquals("", first.instruction)
        assertFalse(first.isSaveable)
    }

    @Test
    fun `saving a skill puts it in front and replaces its previous copy`() {
        val kept = skill(id = "a", name = "A", instruction = "IA")
        val edited = skill(id = "b", name = "B2", instruction = "IB2")

        val next = listOf(edited.copy(name = "B"), kept).upsert(edited)

        assertEquals(listOf("b", "a"), next.map { it.id })
        assertEquals("B2", next.first().name)
        assertEquals("IA", next.last().instruction)
    }

    @Test
    fun `deleting a skill drops only that skill and clears its session references`() {
        val library = listOf(skill(id = "a"), skill(id = "b"))
        assertEquals(listOf("b"), library.without("a").map { it.id })
        assertEquals(listOf("a", "b"), library.without("missing").map { it.id })

        val sessions = listOf(
            session(id = "s1", skillId = "gone"),
            session(id = "s2", skillId = "kept"),
            session(id = "s3", skillId = null),
        )

        val cleared = sessions.clearingSkill("gone")

        assertEquals(listOf(null, "kept", null), cleared.map { it.skillId })
        assertEquals(sessions.map { it.id }, cleared.map { it.id })
    }

    @Test
    fun `a session without a skill contributes no instruction`() {
        assertNull(skillInstruction(listOf(skill(id = "k")), session(id = "s", skillId = null)))
        assertNull(skillInstruction(listOf(skill(id = "k")), session = null))
        assertNull(skillInstruction(listOf(skill(id = "k")), session(id = "s", skillId = "gone")))
    }

    @Test
    fun `a blank instruction is treated as no instruction`() {
        assertNull(skillInstruction(listOf(skill(id = "k", instruction = "   ")), session(id = "s", skillId = "k")))
    }

    @Test
    fun `the selected skill's instruction is trimmed`() {
        val skills = listOf(skill(id = "k", instruction = "  先列风险 \n"))

        assertEquals("先列风险", skillInstruction(skills, session(id = "s", skillId = "k")))
    }

    @Test
    fun `no character and no skill sends no system prompt`() {
        assertNull(sessionSystemPrompt(emptyList(), emptyList(), session(id = "s")))
        assertNull(sessionSystemPrompt(emptyList(), emptyList(), session = null))
    }

    @Test
    fun `a character alone is sent as-is`() {
        val characters = listOf(character(id = "c", prompt = "你是审阅者"))

        assertEquals(
            "你是审阅者",
            sessionSystemPrompt(characters, emptyList(), session(id = "s", characterId = "c")),
        )
    }

    @Test
    fun `a skill alone is introduced by the heading`() {
        val skills = listOf(skill(id = "k", instruction = "先列风险"))

        assertEquals(
            "$SKILL_PROMPT_HEADING\n先列风险",
            sessionSystemPrompt(emptyList(), skills, session(id = "s", skillId = "k")),
        )
        assertEquals("## Skill", SKILL_PROMPT_HEADING)
    }

    @Test
    fun `a character and a skill are combined with the heading between them`() {
        val characters = listOf(character(id = "c", prompt = "你是审阅者"))
        val skills = listOf(skill(id = "k", instruction = "先列三个风险"))

        assertEquals(
            "你是审阅者\n\n$SKILL_PROMPT_HEADING\n先列三个风险",
            sessionSystemPrompt(
                characters,
                skills,
                session(id = "s", characterId = "c", skillId = "k"),
            ),
        )
    }

    @Test
    fun `a dangling selection falls back to whatever still exists`() {
        val characters = listOf(character(id = "c", prompt = "你是审阅者"))
        val skills = listOf(skill(id = "k", instruction = "先列风险"))

        assertEquals(
            "你是审阅者",
            sessionSystemPrompt(
                characters,
                skills,
                session(id = "s", characterId = "c", skillId = "deleted"),
            ),
        )
    }

    private fun skill(
        id: String = "k1",
        name: String = "先列风险",
        instruction: String = "先列三个风险",
    ) = EvSkill(
        id = id,
        name = name,
        description = "描述",
        instruction = instruction,
        updatedAt = 1L,
    )

    private fun character(id: String, prompt: String) = EvCharacter(
        id = id,
        name = "角色",
        description = "",
        systemPrompt = prompt,
        updatedAt = 1L,
    )

    private fun session(
        id: String,
        characterId: String? = null,
        skillId: String? = null,
    ) = ChatSession(
        id = id,
        title = "会话",
        messages = emptyList(),
        updatedAt = 1L,
        characterId = characterId,
        skillId = skillId,
    )
}
