package ev.buan.plugins.ev.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored shape of the skills library: one `skills.json` document in the plugin's storage area.
 *
 * Same arrangement as [CharacterDocuments] and [ChatSessionDocuments]: the mapping lives here so a
 * plain JVM test can exercise it (the plugin uses the platform `org.json`, the tests use the
 * standalone build), and the reader is forgiving - an unreadable document means an empty library
 * instead of a page that cannot open. A skill without an id cannot be selected or edited, so it is
 * dropped; a blank name or instruction is kept as stored, because the editor is the place that
 * demands them, not the decoder.
 */
internal object SkillDocuments {
    /** Relative to the plugin's storage root; the host rejects anything that escapes it. */
    const val PATH: String = "skills.json"

    /** Bumped only when a change could not be read by the previous decoder. */
    const val VERSION: Int = 1

    fun encode(skills: List<EvSkill>): ByteArray {
        val entries = JSONArray()
        skills.forEach { entries.put(it.toJson()) }
        return JSONObject()
            .put("version", VERSION)
            .put("skills", entries)
            .toString()
            .encodeToByteArray()
    }

    /** The stored skills, or null when these bytes are not a document this codec knows. */
    fun decode(bytes: ByteArray): List<EvSkill>? {
        val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return null
        val entries = document.optJSONArray("skills") ?: return null
        return buildList {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.toSkill()?.let { add(it) }
            }
        }
    }
}

private fun EvSkill.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("description", description)
    .put("instruction", instruction)
    .put("updatedAt", updatedAt)

private fun JSONObject.toSkill(): EvSkill? {
    // Without an id the entry cannot be selected, edited or deleted.
    val skillId = optString("id").takeIf { it.isNotBlank() } ?: return null
    return EvSkill(
        id = skillId,
        name = optString("name").trim(),
        description = optString("description").trim(),
        instruction = optString("instruction").trim(),
        updatedAt = optLong("updatedAt", 0L),
    )
}
