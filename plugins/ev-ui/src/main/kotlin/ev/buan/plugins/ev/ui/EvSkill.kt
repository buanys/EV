package ev.buan.plugins.ev.ui

import java.util.UUID

/**
 * One skill in the local skills library.
 *
 * A skill is *text*: enabling it on a session adds its [instruction] to that session's system prompt
 * under a [SKILL_PROMPT_HEADING] block. EV has no skill runtime and nothing here executes anything -
 * a skill cannot run code in the host, it can only tell the model how to answer, exactly like a
 * character persona. It never becomes a chat bubble and never enters agent memory.
 */
internal data class EvSkill(
    val id: String,
    val name: String,
    val description: String,
    val instruction: String,
    val updatedAt: Long,
) {
    /** The editor's only save rule: a skill without a name or an instruction is not usable. */
    val isSaveable: Boolean get() = name.isNotBlank() && instruction.isNotBlank()
}

internal fun newSkillId(): String = UUID.randomUUID().toString()

/** A blank skill for the "new" editor: it owns an id before the user has typed anything. */
internal fun newSkill(now: Long = System.currentTimeMillis()): EvSkill = EvSkill(
    id = newSkillId(),
    name = "",
    description = "",
    instruction = "",
    updatedAt = now,
)

/**
 * Puts [skill] at the head of the library, replacing the stored copy with the same id.
 *
 * The library is newest-first, so the skill just saved is the one the user sees on top.
 */
internal fun List<EvSkill>.upsert(skill: EvSkill): List<EvSkill> =
    listOf(skill) + filterNot { it.id == skill.id }

/** Drops [id]; an unknown id leaves the library untouched. */
internal fun List<EvSkill>.without(id: String): List<EvSkill> = filterNot { it.id == id }

/** Clears references to a deleted skill, so no session points at a skill that no longer exists. */
internal fun List<ChatSession>.clearingSkill(skillId: String): List<ChatSession> = map { session ->
    if (session.skillId == skillId) session.copy(skillId = null) else session
}

/** Heading that introduces a skill block inside the combined system prompt. */
internal const val SKILL_PROMPT_HEADING: String = "## Skill"

/**
 * The instruction [session]'s selected skill contributes, or null when the session has no skill, the
 * skill was deleted, or its instruction is blank.
 */
internal fun skillInstruction(skills: List<EvSkill>, session: ChatSession?): String? = session
    ?.skillId
    ?.let { id -> skills.firstOrNull { it.id == id } }
    ?.instruction
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/**
 * The system prompt for [session]'s next turn: the character's persona first, then the selected
 * skill's instruction under [SKILL_PROMPT_HEADING]. Null when the session has neither, so a plain
 * conversation still sends no prompt at all (the brain then behaves exactly as before).
 */
internal fun sessionSystemPrompt(
    characters: List<EvCharacter>,
    skills: List<EvSkill>,
    session: ChatSession?,
): String? {
    val character = characterPrompt(characters, session)
    val skillBlock = skillInstruction(skills, session)?.let { "$SKILL_PROMPT_HEADING\n$it" }
    return when {
        character == null -> skillBlock
        skillBlock == null -> character
        else -> "$character\n\n$skillBlock"
    }
}
