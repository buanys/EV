package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.MemoryService
import org.cordis.api.PluginStorage

/**
 * Conversation memory as one JSON document per session in the plugin's own [PluginStorage].
 *
 * JSON rather than Room: a plugin only receives the Cordis context, not an Android `Context`, so a
 * Room database cannot be opened from here. A session is a small append-only document, which one
 * atomic write covers; the host still accounts for the bytes against the plugin quota. The stored
 * shape itself lives in [MemoryDocuments].
 *
 * Every operation serialises on one JVM monitor: history/append form a read-modify-write pair and
 * both are blocking file calls, so a coroutine mutex (and the dependency it implies) is not needed.
 */
internal class StorageMemoryService(
    private val storage: PluginStorage,
    private val index: MemoryIndex = MemoryIndex(storage),
    /**
     * Called after a message is durably stored, outside the lock.
     *
     * The memory plugin uses it to notice "the user came back after a quiet spell", which is when
     * distillation is allowed to run. Kept as a callback rather than a dependency so this class
     * still works (and is still testable) without a model or a coroutine scope.
     */
    private val onAppend: ((String) -> Unit)? = null,
) : MemoryService {
    private val lock = Any()

    override suspend fun history(sessionId: String): List<AgentMessage> = synchronized(lock) {
        read(sessionId)
    }

    override suspend fun append(sessionId: String, message: AgentMessage) {
        synchronized(lock) {
            val messages = read(sessionId) + message
            write(sessionId, messages)
            // The index is rebuilt from what was actually written, so a lost index heals itself on
            // the next message instead of leaving the page permanently empty.
            index.touch(sessionId, messages)
        }
        // Outside the lock: the callback reads history and may start a model call, and holding the
        // append lock across either would block the next turn behind a summarisation.
        onAppend?.invoke(sessionId)
    }

    override suspend fun clear(sessionId: String) {
        synchronized(lock) {
            storage.delete(MemoryDocuments.fileFor(sessionId))
            index.remove(sessionId)
        }
    }

    /** Sessions this service has stored, most recently active first. Used by the plugin's own page. */
    fun sessions(): List<MemorySession> = index.list()

    private fun read(sessionId: String): List<AgentMessage> {
        val path = MemoryDocuments.fileFor(sessionId)
        val bytes = storage.read(path) ?: return emptyList()
        return MemoryDocuments.decode(sessionId, bytes)
    }

    private fun write(sessionId: String, messages: List<AgentMessage>) {
        storage.atomicWrite(
            MemoryDocuments.fileFor(sessionId),
            MemoryDocuments.encode(sessionId, messages),
        )
    }
}
