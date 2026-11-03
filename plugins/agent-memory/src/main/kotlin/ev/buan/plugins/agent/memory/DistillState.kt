package ev.buan.plugins.agent.memory

import org.json.JSONObject

/**
 * The distillation bookkeeping: how far each session has been covered, when it last moved, and
 * whether a batch is currently in flight.
 *
 * Why this is its own document rather than fields on the notes: it is a **cursor**, not content.
 * Losing it costs one re-distillation (the notes are de-duplicated on title anyway); letting it
 * block a conversation would cost the user their turn.
 *
 * `pending` is written *before* the model call and cleared after the notes are committed. A
 * pending entry that outlives the timeout means the process died mid-distillation. The batch is
 * then retried from the same cursor - there is no half-written batch to clean up, because notes
 * are only written after the whole batch was parsed and validated.
 */
internal data class DistillState(
    val coveredUpTo: Map<String, Int> = emptyMap(),
    val lastMessageAt: Map<String, Long> = emptyMap(),
    val pending: Map<String, Pending> = emptyMap(),
) {

    /** One in-flight batch: the message range it is reading, and when it started. */
    data class Pending(val from: Int, val to: Int, val startedAt: Long)

    fun encode(): ByteArray = JSONObject().apply {
        put(
            "coveredUpTo",
            JSONObject().apply { coveredUpTo.forEach { (session, seq) -> put(session, seq) } },
        )
        put(
            "lastMessageAt",
            JSONObject().apply { lastMessageAt.forEach { (session, at) -> put(session, at) } },
        )
        put(
            "pending",
            JSONObject().apply {
                pending.forEach { (session, batch) ->
                    put(
                        session,
                        JSONObject()
                            .put("from", batch.from)
                            .put("to", batch.to)
                            .put("startedAt", batch.startedAt),
                    )
                }
            },
        )
    }.toString().encodeToByteArray()

    fun covered(sessionId: String): Int = coveredUpTo[sessionId] ?: 0

    fun lastAt(sessionId: String): Long = lastMessageAt[sessionId] ?: 0L

    fun withCovered(sessionId: String, upTo: Int): DistillState =
        copy(coveredUpTo = coveredUpTo + (sessionId to upTo))

    fun withLastAt(sessionId: String, at: Long): DistillState =
        copy(lastMessageAt = lastMessageAt + (sessionId to at))

    fun withPending(sessionId: String, batch: Pending?): DistillState =
        copy(
            pending = if (batch == null) pending - sessionId else pending + (sessionId to batch),
        )

    companion object {
        /** Relative path inside the plugin's `UserDataStorage`. */
        const val PATH = "memory/distill.json"

        fun decode(bytes: ByteArray): DistillState {
            val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull()
                ?: return DistillState()
            val covered = document.optJSONObject("coveredUpTo").toIntMap()
            val lastAt = document.optJSONObject("lastMessageAt").toLongMap()
            val pending = mutableMapOf<String, Pending>()
            document.optJSONObject("pending")?.let { entries ->
                entries.keys().forEach { session ->
                    val item = entries.optJSONObject(session) ?: return@forEach
                    pending[session] = Pending(
                        from = item.optInt("from", 0),
                        to = item.optInt("to", 0),
                        startedAt = item.optLong("startedAt", 0),
                    )
                }
            }
            return DistillState(covered, lastAt, pending)
        }

        private fun JSONObject?.toIntMap(): Map<String, Int> {
            if (this == null) return emptyMap()
            val result = HashMap<String, Int>()
            keys().forEach { key -> result[key] = optInt(key, 0) }
            return result
        }

        private fun JSONObject?.toLongMap(): Map<String, Long> {
            if (this == null) return emptyMap()
            val result = HashMap<String, Long>()
            keys().forEach { key -> result[key] = optLong(key, 0) }
            return result
        }
    }
}
