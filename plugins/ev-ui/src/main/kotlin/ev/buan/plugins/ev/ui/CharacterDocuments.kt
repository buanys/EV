package ev.buan.plugins.ev.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored shape of the character library: one `characters.json` document in the plugin's storage
 * area.
 *
 * Same arrangement as [ChatSessionDocuments]: the mapping lives here so a plain JVM test can exercise
 * it (the plugin uses the platform `org.json`, the tests use the standalone build), and the reader is
 * forgiving - an unreadable document means an empty library instead of a page that cannot open. A
 * character without an id cannot be addressed by a session (nor edited), so it is dropped; a blank
 * name or prompt is kept as stored, because the editor is the place that demands them, not the
 * decoder.
 */
internal object CharacterDocuments {
    /** Relative to the plugin's storage root; the host rejects anything that escapes it. */
    const val PATH: String = "characters.json"

    /** Bumped only when a change could not be read by the previous decoder. */
    const val VERSION: Int = 1

    fun encode(characters: List<EvCharacter>): ByteArray {
        val entries = JSONArray()
        characters.forEach { entries.put(it.toJson()) }
        return JSONObject()
            .put("version", VERSION)
            .put("characters", entries)
            .toString()
            .encodeToByteArray()
    }

    /** The stored characters, or null when these bytes are not a document this codec knows. */
    fun decode(bytes: ByteArray): List<EvCharacter>? {
        val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return null
        val entries = document.optJSONArray("characters") ?: return null
        return buildList {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.toCharacter()?.let { add(it) }
            }
        }
    }
}

private fun EvCharacter.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("description", description)
    .put("systemPrompt", systemPrompt)
    .put("updatedAt", updatedAt)

private fun JSONObject.toCharacter(): EvCharacter? {
    // Without an id the entry cannot be selected, edited or deleted.
    val characterId = optString("id").takeIf { it.isNotBlank() } ?: return null
    return EvCharacter(
        id = characterId,
        name = optString("name").trim(),
        description = optString("description").trim(),
        systemPrompt = optString("systemPrompt").trim(),
        updatedAt = optLong("updatedAt", 0L),
    )
}
