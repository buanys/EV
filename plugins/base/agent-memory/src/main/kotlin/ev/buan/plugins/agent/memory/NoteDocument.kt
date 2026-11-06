package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryNote
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored shape of the notebook: one JSON document holding every L2 note.
 *
 * One document rather than one file per note: notes are small and read together (recall scans all
 * of them), so a single atomic write is both simpler and cheaper than a directory of files that
 * would need an index anyway. The file is a few hundred KB at most; the host accounts for it
 * against the plugin quota.
 *
 * Split out of [NoteStore] so the shape can be tested without a host, and because the rules here
 * are the ones that corrupt data when wrong: an unreadable document is never overwritten, and a
 * note that cannot be decoded is skipped instead of taking the whole notebook down with it.
 */
internal object NoteDocument {

    /** Relative path inside the plugin's `UserDataStorage`. */
    const val PATH = "memory/notes.json"

    fun encode(notes: List<MemoryNote>): ByteArray {
        val array = JSONArray()
        notes.forEach { array.put(it.toJson()) }
        return JSONObject().put("version", 1).put("notes", array).toString().encodeToByteArray()
    }

    fun decode(bytes: ByteArray): List<MemoryNote> {
        val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrElse { error ->
            // Keep the unreadable file intact: the next write would destroy the only copy.
            throw IllegalStateException("记忆条目文件无法解析，已保留原文件：$PATH", error)
        }
        val entries = document.optJSONArray("notes") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            entries.optJSONObject(index)?.toNote()
        }
    }
}

/** Shared with the tools, which return a note to the model as JSON. */
internal fun MemoryNote.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("kind", kind)
    put("body", body)
    put("keywords", JSONArray(keywords))
    put("sources", JSONArray(sources))
    put("sessions", JSONArray(sessions))
    put("status", status)
    put("impact", impact)
    put("origin", origin)
    put("pinned", pinned)
    put("pinOrder", pinOrder)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    supersedes?.let { put("supersedes", it) }
}

private fun JSONObject.toNote(): MemoryNote? {
    val id = optString("id", "").trim()
    val title = optString("title", "").trim()
    // A note without an id cannot be referenced or updated; without a title it is unreadable in
    // the resident block. Skip instead of inventing one, so a hand-edited file cannot fill the
    // notebook with anonymous rows.
    if (id.isEmpty() || title.isEmpty()) return null
    return MemoryNote(
        id = id,
        title = title,
        kind = optString("kind", MemoryNote.KIND_FACT).ifEmpty { MemoryNote.KIND_FACT },
        body = optString("body", ""),
        keywords = optStringList("keywords"),
        sources = optStringList("sources"),
        sessions = optStringList("sessions"),
        status = optString("status", MemoryNote.STATUS_ACTIVE).ifEmpty { MemoryNote.STATUS_ACTIVE },
        supersedes = optString("supersedes", "").ifEmpty { null },
        impact = optDouble("impact", 1.0),
        origin = optString("origin", MemoryNote.ORIGIN_MODEL).ifEmpty { MemoryNote.ORIGIN_MODEL },
        pinned = optBoolean("pinned", false),
        pinOrder = optInt("pinOrder", 0),
        createdAt = optLong("createdAt", 0),
        updatedAt = optLong("updatedAt", 0),
    )
}

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index, "").trim().ifEmpty { null }
    }
}
