package ev.buan.plugins.eta.ui

import ev.buan.plugins.agent.api.AgentEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stored shape of the chat history: one `sessions.json` document in this plugin's storage area.
 *
 * Split out of [ChatSessionStore] so the mapping can be tested on a plain JVM - the plugin uses the
 * platform `org.json` at runtime (never packaged) while the tests use the standalone build, the same
 * arrangement `agent-memory` uses for its memory documents.
 *
 * The reader is deliberately forgiving: a chat is a UI convenience, not the agent's memory, so an
 * unreadable document means "start from an empty history" instead of a page that cannot open (see
 * [ChatSessionStore]). Inside a readable document, entries that cannot stand on their own - a
 * session or message without an id - are dropped so the rest still loads; unknown keys, including a
 * newer [VERSION], are ignored rather than rejected.
 */
internal object ChatSessionDocuments {
    /** Relative to the plugin's storage root; the host rejects anything that escapes it. */
    const val PATH: String = "sessions.json"

    /** Bumped only when a change could not be read by the previous decoder. */
    const val VERSION: Int = 1

    fun encode(sessions: List<ChatSession>): ByteArray {
        val entries = JSONArray()
        sessions.forEach { entries.put(it.toJson()) }
        return JSONObject()
            .put("version", VERSION)
            .put("sessions", entries)
            .toString()
            .encodeToByteArray()
    }

    /** The stored conversations, or null when these bytes are not a document this codec knows. */
    fun decode(bytes: ByteArray): List<ChatSession>? {
        val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return null
        val entries = document.optJSONArray("sessions") ?: return null
        return buildList {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.toSession()?.let { add(it) }
            }
        }
    }
}

private fun ChatSession.toJson(): JSONObject {
    val messages = JSONArray()
    this.messages.forEach { messages.put(it.toJson()) }
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("updatedAt", updatedAt)
        .put("messages", messages)
        // Absent for a session without a role or a skill: the old format had no such keys, and a null
        // is written as "no key" so a plain document stays byte-compatible with earlier builds.
        .also { json ->
            characterId?.let { json.put("characterId", it) }
            skillId?.let { json.put("skillId", it) }
        }
}

private fun JSONObject.toSession(): ChatSession? {
    // The id is the drawer key and the LazyColumn key, so a session without one cannot be restored.
    val sessionId = optString("id").takeIf { it.isNotBlank() } ?: return null
    val entries = optJSONArray("messages")
    return ChatSession(
        id = sessionId,
        title = optString("title").ifBlank { DEFAULT_SESSION_TITLE },
        messages = entries?.readMessages().orEmpty(),
        updatedAt = optLong("updatedAt", 0L),
        // Files written before the character/skill libraries existed simply have no key here.
        characterId = optString("characterId").ifBlank { null },
        skillId = optString("skillId").ifBlank { null },
    )
}

private fun ChatMessage.toJson(): JSONObject {
    val json = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("fromUser", fromUser)
    // Tool details are a bonus, not the message: a turn whose events cannot be written still keeps
    // its id, text and side, so the conversation stays readable after a degraded save.
    runCatching {
        JSONArray().also { array -> events.forEach { event -> array.put(event.toJson()) } }
    }.getOrNull()?.let { json.put("events", it) }
    return json
}

private fun JSONObject.toMessage(): ChatMessage? {
    val messageId = optString("id").takeIf { it.isNotBlank() } ?: return null
    return ChatMessage(
        id = messageId,
        text = optString("text"),
        fromUser = optBoolean("fromUser", false),
        events = optJSONArray("events")?.readEvents().orEmpty(),
    )
}

private fun JSONArray.readMessages(): List<ChatMessage> = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)?.toMessage()?.let { add(it) }
    }
}

private fun JSONArray.readEvents(): List<AgentEvent> = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)?.toEvent()?.let { add(it) }
    }
}

private fun AgentEvent.toJson(): JSONObject {
    val json = JSONObject().put("kind", kind.name)
    text?.let { json.put("text", it) }
    toolName?.let { json.put("toolName", it) }
    toolCallId?.let { json.put("toolCallId", it) }
    argumentsJson?.let { json.put("argumentsJson", it) }
    return json
}

private fun JSONObject.toEvent(): AgentEvent? {
    // A kind this build does not know is one step less to show, not a reason to drop the reply.
    val kind = AgentEvent.Kind.entries.firstOrNull { it.name == optString("kind") } ?: return null
    return AgentEvent(
        kind = kind,
        text = optString("text").ifEmpty { null },
        toolName = optString("toolName").ifEmpty { null },
        toolCallId = optString("toolCallId").ifEmpty { null },
        argumentsJson = optString("argumentsJson").ifEmpty { null },
    )
}
