package ev.buan.plugins.eta.ui

import java.util.UUID

/**
 * One persona in the local character library.
 *
 * A character is three pieces of text: a [name] the user picks from, a [description] for the list,
 * and the [systemPrompt] the agent receives for every turn of a session that selects it. The prompt
 * is what makes a character real, so [isSaveable] refuses a name without one.
 *
 * Nothing here stops the character from affecting anything but the model input: the prompt travels
 * only as `AgentService.send(systemPrompt = ...)`, and the brain places it ahead of the stored
 * history without persisting it. It never becomes a chat bubble and never enters agent memory.
 */
internal data class EtaCharacter(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val updatedAt: Long,
) {
    /** The editor's only save rule: a character without a name or an instruction is not usable. */
    val isSaveable: Boolean get() = name.isNotBlank() && systemPrompt.isNotBlank()
}

internal fun newCharacterId(): String = UUID.randomUUID().toString()

/** A blank character for the "new" editor: it owns an id before the user has typed anything. */
internal fun newCharacter(now: Long = System.currentTimeMillis()): EtaCharacter = EtaCharacter(
    id = newCharacterId(),
    name = "",
    description = "",
    systemPrompt = "",
    updatedAt = now,
)

/**
 * Puts [character] at the head of the library, replacing the stored copy with the same id.
 *
 * The library is newest-first, so the character just saved is the one the user sees on top.
 */
internal fun List<EtaCharacter>.upsert(character: EtaCharacter): List<EtaCharacter> =
    listOf(character) + filterNot { it.id == character.id }

/** Drops [id]; an unknown id leaves the library untouched. */
internal fun List<EtaCharacter>.without(id: String): List<EtaCharacter> = filterNot { it.id == id }

/** Clears references to a deleted character, so no session points at a role that no longer exists. */
internal fun List<ChatSession>.clearingCharacter(characterId: String): List<ChatSession> = map { session ->
    if (session.characterId == characterId) session.copy(characterId = null) else session
}

/**
 * The instruction to send with [session]'s next turn: the selected character's prompt, or null when
 * the session has no character, the character was deleted, or its prompt is blank.
 */
internal fun characterPrompt(characters: List<EtaCharacter>, session: ChatSession?): String? = session
    ?.characterId
    ?.let { id -> characters.firstOrNull { it.id == id } }
    ?.systemPrompt
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
