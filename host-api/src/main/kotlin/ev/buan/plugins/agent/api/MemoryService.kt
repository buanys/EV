package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * Conversation memory published by a memory plugin.
 *
 * Sessions are opaque ids chosen by the caller; the storage format belongs to the implementation's
 * own `PluginStorage`, never to this contract.
 */
interface MemoryService {
    /** Messages stored for [sessionId], oldest first. An unknown session is an empty list. */
    suspend fun history(sessionId: String): List<AgentMessage>

    /** Appends [message] to [sessionId], creating the session when needed. */
    suspend fun append(sessionId: String, message: AgentMessage)

    /** Drops everything stored for [sessionId]. */
    suspend fun clear(sessionId: String)

    companion object {
        val Key = ServiceKey<MemoryService>("agent/memory")
    }
}
