package ev.buan.plugins.agent.log

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cordis.api.PluginStorage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 把会话事件落进 per-session 的 JSONL。
 *
 * 为什么不复用全局那条 `events.jsonl`：全局流是**审计**用的（sys / sensor / proposal /
 * verdict …），序号跨会话连续，缺一条就是缺一条；会话日志是**投影**用的，按会话分片，
 * 一个会话被清空不该在别的会话里留下一个洞。两者共用 [EventLog] 的写入纪律（单写者、
 * seq 严格 +1、不可改写），但文件不是一个文件。
 *
 * ## 为什么每条事件都重写全文
 *
 * [EventLog] 的 append 是"读全文 → 拼一行 → 写全文"。对全局审计流这是刻意的保守选择；
 * 对单会话文件也够用——一次几十分钟的对话是几百条事件、几百 KB 量级，一次重写是毫秒级。
 * 真正的优化点（追加写 + 快照分段）等有了量再说，现在加只会多一个半吊子的写入路径。
 *
 * ## 文件名
 *
 * `sessions/<sha256(sessionId) 前 16 位>.jsonl`。sessionId 来自 UI，可能是任意字符串，
 * 直接拼进路径就等于把路径穿越交给调用方。哈希掉了可读性，但事件里记着原始的
 * actor 与内容，排查时不需要靠文件名认人。
 */
class SessionLogService(private val storage: PluginStorage) : SessionLog {

    /** 同一会话的写入必须串行，否则两条 append 会读到同一个"上一条 seq"。 */
    private val writeLock = Mutex()

    /** EventLog 无状态（除 lastRecovery），按会话复用，省掉每次都新建存储对象的开销。 */
    private val logs = HashMap<String, EventLog>()

    override suspend fun append(sessionId: String, event: SessionEvent) {
        val (type, payload) = encode(event)
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                logOf(sessionId).append(type = type, actor = actorOf(event), payload = payload)
            }
        }
    }

    override suspend fun messages(sessionId: String): List<AgentMessage> =
        decode(sessionId).mapNotNull { event ->
            (event as? SessionEvent.Message)?.message
        }

    override suspend fun events(sessionId: String): List<SessionEvent> =
        decode(sessionId)

    override suspend fun clear(sessionId: String) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                storeOf(sessionId).writeText("")
            }
        }
    }

    // ---------------------------------------------------------------- 读

    private suspend fun decode(sessionId: String): List<SessionEvent> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            logOf(sessionId).readAll().mapNotNull { raw ->
                when (raw.type) {
                    TYPE_MESSAGE -> decodeMessage(raw.payload)?.let { SessionEvent.Message(it, raw.ts) }
                    TYPE_TURN_END -> decodeStop(raw.payload)?.let { SessionEvent.TurnEnded(it, raw.ts) }
                    else -> null
                }
            }
        }
    }

    // ---------------------------------------------------------------- 编码

    private fun encode(event: SessionEvent): Pair<String, JSONObject> = when (event) {
        is SessionEvent.Message -> TYPE_MESSAGE to messageJson(event.message).apply { put("at", event.at) }
        is SessionEvent.TurnEnded -> TYPE_TURN_END to stopJson(event.stop).apply { put("at", event.at) }
    }

    /** 写入方只有两个：用户的消息与模型/工具的产出。审计流里的 actor 分得更细，这里够用。 */
    private fun actorOf(event: SessionEvent): String = when (event) {
        is SessionEvent.Message -> when (event.message.role) {
            AgentRole.USER -> "user"
            AgentRole.SYSTEM -> "host"
            AgentRole.ASSISTANT, AgentRole.TOOL -> "agent:brain"
        }

        is SessionEvent.TurnEnded -> "host"
    }

    private fun messageJson(message: AgentMessage): JSONObject = JSONObject().apply {
        put("role", message.role.name)
        put("content", message.content)
        message.name?.let { put("name", it) }
        message.toolCallId?.let { put("toolCallId", it) }
        if (message.toolCalls.isNotEmpty()) {
            put(
                "toolCalls",
                JSONArray().apply {
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("name", call.name)
                                put("argumentsJson", call.argumentsJson)
                            },
                        )
                    }
                },
            )
        }
        // images 刻意不写：见 SessionEvent.Message 的注释（transient、体积大、可重读）。
    }

    private fun stopJson(stop: AgentStop): JSONObject = JSONObject().apply {
        put("reason", stop.reason.name)
        put("rounds", stop.rounds)
        put("toolCalls", stop.toolCalls)
        stop.detail?.let { put("detail", it) }
    }

    // ---------------------------------------------------------------- 解码

    private fun decodeMessage(payload: JSONObject): AgentMessage? {
        val role = runCatching { AgentRole.valueOf(payload.optString("role", "")) }.getOrNull()
            ?: return null
        val content = payload.optString("content", "")
        val calls = payload.optJSONArray("toolCalls")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                AgentToolCall(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    argumentsJson = obj.optString("argumentsJson", ""),
                )
            }
        }.orEmpty()
        return AgentMessage(
            role = role,
            content = content,
            name = payload.optString("name", "").takeIf { it.isNotEmpty() },
            toolCallId = payload.optString("toolCallId", "").takeIf { it.isNotEmpty() },
            toolCalls = calls,
        )
    }

    private fun decodeStop(payload: JSONObject): AgentStop? {
        val reason = runCatching { AgentStop.Reason.valueOf(payload.optString("reason", "")) }
            .getOrNull() ?: return null
        return AgentStop(
            reason = reason,
            rounds = payload.optInt("rounds", 0),
            toolCalls = payload.optInt("toolCalls", 0),
            detail = payload.optString("detail", "").takeIf { it.isNotEmpty() },
        )
    }

    // ---------------------------------------------------------------- 存储

    private fun logOf(sessionId: String): EventLog = logs.getOrPut(sessionId) {
        EventLog(store = PluginStorageLogStore(storage, pathOf(sessionId)))
    }

    private fun storeOf(sessionId: String): LogStore =
        PluginStorageLogStore(storage, pathOf(sessionId))

    private fun pathOf(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (byte in digest.take(16)) {
                append(HEX[(byte.toInt() shr 4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }
        return "$SESSION_DIR/$hex.jsonl"
    }

    private companion object {
        const val SESSION_DIR = "sessions"
        const val TYPE_MESSAGE = "session.message"
        const val TYPE_TURN_END = "session.turn-end"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
