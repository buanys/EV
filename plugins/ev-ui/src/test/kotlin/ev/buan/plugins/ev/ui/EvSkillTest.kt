package ev.buan.plugins.ev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the skills library rules and - the part the chat page depends on - how a session's skill is
 * folded into the system prompt.
 *
 * Characters used to live here too. They were removed on 2026-11-03: a character was a second,
 * per-session copy of "who you are", and the prompt plugin's persona layer already owns that.
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
        assertEquals("gone", sessions.first().skillId)
    }

    @Test
    fun `a session with no skill contributes nothing`() {
        val skills = listOf(skill(id = "k", instruction = "先列风险"))

        assertNull(skillInstruction(skills, session(id = "s", skillId = null)))
        assertNull(skillInstruction(skills, session(id = "s", skillId = "deleted")))
        assertNull(skillInstruction(skills, null))
        assertEquals("先列风险", skillInstruction(skills, session(id = "s", skillId = "k")))
    }

    @Test
    fun `no skill sends no system prompt`() {
        assertNull(sessionSystemPrompt(emptyList(), session(id = "s")))
        assertNull(sessionSystemPrompt(emptyList(), session = null))
    }

    @Test
    fun `a skill is introduced by the heading`() {
        val skills = listOf(skill(id = "k", instruction = "先列风险"))

        assertEquals(
            "$SKILL_PROMPT_HEADING\n先列风险",
            sessionSystemPrompt(skills, session(id = "s", skillId = "k")),
        )
        assertEquals("## Skill", SKILL_PROMPT_HEADING)
    }

    @Test
    fun `a dangling selection falls back to nothing`() {
        val skills = listOf(skill(id = "k", instruction = "先列风险"))

        assertNull(sessionSystemPrompt(skills, session(id = "s", skillId = "deleted")))
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

    private fun session(
        id: String,
        skillId: String? = null,
    ) = ChatSession(
        id = id,
        title = "会话",
        messages = emptyList(),
        updatedAt = 1L,
        skillId = skillId,
    )
}
