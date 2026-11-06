package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The stored shape of one session's memory: a JSON document under `memory/sessions/`.
 *
 * Split out of [StorageMemoryService] so it can be tested without a host. Two rules matter here and
 * are easy to get wrong: the file name is derived from the id (the id comes from another plugin, so
 * it must never become a path), and a reader that cannot parse the document fails loudly instead of
 * reporting an empty history that the next append would overwrite.
 */
internal object MemoryDocuments {
    /** Relative directory inside the plugin's own `PluginStorage` area. */
    const val DIRECTORY = "memory/sessions"

    /** SHA-256 of the id: fixed length, so no id can escape the directory or hit a reserved name. */
    fun fileFor(sessionId: String): String {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.encodeToByteArray())
        return "$DIRECTORY/${digest.joinToString("") { "%02x".format(it) }}.json"
    }

    fun encode(sessionId: String, messages: List<AgentMessage>): ByteArray {
        val entries = JSONArray()
        messages.forEach { entries.put(it.toJson()) }
        return JSONObject()
            .put("session", sessionId)
            .put("messages", entries)
            .toString()
            .encodeToByteArray()
    }

    fun decode(sessionId: String, bytes: ByteArray): List<AgentMessage> {
        val document = runCatching { JSONObject(bytes.decodeToString()) }.getOrElse { error ->
            // Keep the unreadable file intact: overwriting it would destroy the only copy.
            throw IllegalStateException("记忆文件无法解析，已保留原文件：${fileFor(sessionId)}", error)
        }
        val storedSession = document.optString("session", "")
        check(storedSession == sessionId) { "记忆文件不属于该会话：${fileFor(sessionId)}" }
        val entries = document.optJSONArray("messages") ?: return emptyList()
        return (0 until entries.length()).map { index -> entries.messageAt(index) }
    }
}

private fun JSONArray.messageAt(index: Int): AgentMessage {
    val entry = optJSONObject(index) ?: error("记忆文件第 ${index + 1} 条不是对象")
    val roleName = entry.optString("role", "")
    val role = AgentRole.entries.firstOrNull { it.name == roleName }
        ?: error("记忆文件第 ${index + 1} 条的角色未知：$roleName")
    return AgentMessage(
        role = role,
        content = entry.optString("content", ""),
        name = entry.optString("name", "").ifEmpty { null },
        toolCallId = entry.optString("toolCallId", "").ifEmpty { null },
        // Written by versions before the native tool protocol: no field, no calls.
        toolCalls = entry.toolCallsAt(),
    )
}

private fun JSONObject.toolCallsAt(): List<AgentToolCall> {
    val array = optJSONArray("toolCalls") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { call ->
            AgentToolCall(
                id = call.optString("id", ""),
                name = call.optString("name", ""),
                argumentsJson = call.optString("argumentsJson", ""),
            )
        }
    }
}

private fun AgentMessage.toJson(): JSONObject {
    val json = JSONObject().put("role", role.name).put("content", content)
    name?.let { json.put("name", it) }
    toolCallId?.let { json.put("toolCallId", it) }
    if (toolCalls.isNotEmpty()) {
        val calls = JSONArray()
        toolCalls.forEach { call ->
            calls.put(
                JSONObject()
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("argumentsJson", call.argumentsJson),
            )
        }
        json.put("toolCalls", calls)
    }
    return json
}
