package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import org.cordis.api.PluginStorage
import org.json.JSONArray
import org.json.JSONObject

/** One row of the session index, as the memory page needs it. */
internal data class MemorySession(
    val id: String,
    val title: String,
    val count: Int,
    val lastAt: Long,
)

/**
 * The session index: which conversations have stored messages, how many, and when they last moved.
 *
 * Why a separate document: a session's file name is derived from its id through a hash, which is
 * exactly right for safety (an id coming from another plugin must never become a path) and useless
 * for listing - the directory cannot be turned back into session ids. The index keeps the readable
 * side: id, first line, count, timestamp.
 *
 * It is a cache, not a source of truth. A missing or corrupt index degrades to an empty page, and
 * the next append rebuilds the entry for that session. Nothing else depends on it.
 */
internal class MemoryIndex(private val storage: PluginStorage) {
    private val lock = Any()

    fun touch(sessionId: String, messages: List<AgentMessage>) {
        synchronized(lock) {
            val all = read().associateBy { it.id }.toMutableMap()
            val firstUser = messages.firstOrNull { it.role == AgentRole.USER }
                ?.content?.trim()?.replace('\n', ' ')?.take(TITLE_CHARS)
            all[sessionId] = MemorySession(
                id = sessionId,
                title = firstUser?.takeIf { it.isNotEmpty() }
                    ?: all[sessionId]?.title?.takeIf { it.isNotEmpty() }
                    ?: "未命名对话",
                count = messages.size,
                lastAt = System.currentTimeMillis(),
            )
            write(all.values.toList())
        }
    }

    fun remove(sessionId: String) {
        synchronized(lock) { write(read().filterNot { it.id == sessionId }) }
    }

    /** Most recently active first. */
    fun list(): List<MemorySession> = synchronized(lock) {
        read().sortedByDescending { it.lastAt }
    }

    private fun read(): List<MemorySession> {
        val text = runCatching { storage.read(PATH)?.decodeToString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: JSONObject()
                MemorySession(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    count = item.optInt("count"),
                    lastAt = item.optLong("lastAt"),
                )
            }.filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun write(sessions: List<MemorySession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("count", session.count)
                    put("lastAt", session.lastAt)
                },
            )
        }
        runCatching { storage.atomicWrite(PATH, array.toString().encodeToByteArray()) }
    }

    private companion object {
        const val PATH = "memory/index.json"
        const val TITLE_CHARS = 30
    }
}
