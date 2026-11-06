package ev.buan.plugins.agent.log

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条事件。规格 `/root/specs/01-event-stream.md` §1。
 *
 * 字段刻意与规格一一对应，不多不少：
 * - [seq] 从 1 起严格 +1，不连续就是数据损坏，不能"修好继续"；
 * - [id] 稳定唯一，所有跨引用都用它（`refs`）；
 * - [ts] 事件**发生**时间，不是写入时间（写入延迟见 [EventLog]，不往这里塞）；
 * - [type] 带命名空间前缀：`sys.` `sensor.` `proposal.` `verdict.` `action.` `report.` `rule.`；
 * - [actor] `host` / `code` / `agent:<name>` / `user`；
 * - [payload] 该类型的正文，原样保存（不解析成强类型：类型集会变，落盘格式不能跟着变）；
 * - [refs] 引用的事件 id，可以为空，但字段必须在。
 *
 * 不可变。纠正只能追加一条新事件（`sys.void`），不允许改写已写的行。
 */
data class EvEvent(
    val seq: Int,
    val id: String,
    val ts: Long,
    val type: String,
    val actor: String,
    val payload: JSONObject,
    val refs: List<String>,
) {
    /** 序列化成一行 JSONL。字段顺序固定，便于人读与 diff。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("id", id)
        put("ts", ts)
        put("type", type)
        put("actor", actor)
        put("payload", payload)
        put("refs", JSONArray(refs))
    }

    companion object {
        /**
         * 从一行解析。返回 null 表示这行不是一条合法事件（调用方决定是丢弃还是隔离）。
         *
         * 缺字段一律视为非法——宁可隔离一条可疑事件，也不要让半条事件混进真相源。
         */
        fun fromJson(line: String): EvEvent? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val seq = obj.optInt("seq", 0)
            val id = obj.optString("id", "")
            val type = obj.optString("type", "")
            val actor = obj.optString("actor", "")
            // seq 必须为正、id/type/actor 必须非空：这四项缺任何一个都无法充当真相源。
            if (seq <= 0 || id.isEmpty() || type.isEmpty() || actor.isEmpty()) return null
            if (!obj.has("ts") || !obj.has("payload")) return null
            val refs = mutableListOf<String>()
            val rawRefs = obj.optJSONArray("refs")
            if (rawRefs != null) {
                for (i in 0 until rawRefs.length()) {
                    val r = rawRefs.optString(i, "")
                    if (r.isNotEmpty()) refs += r
                }
            }
            return EvEvent(
                seq = seq,
                id = id,
                ts = obj.optLong("ts", 0L),
                type = type,
                actor = actor,
                payload = obj.optJSONObject("payload") ?: JSONObject(),
                refs = refs,
            )
        }
    }
}

/** 类型前缀。沿用规格的七个命名空间。 */
object EvTypes {
    const val SYS = "sys."
    const val SENSOR = "sensor."
    const val PROPOSAL = "proposal."
    const val VERDICT = "verdict."
    const val ACTION = "action."
    const val REPORT = "report."
    const val RULE = "rule."

    /** 新增 `sys.gap`：恢复时发现序号缺口用。 */
    const val SYS_GAP = "sys.gap"
    /** 新增 `sys.void`：作废一条历史事件（纠正的唯一方式）。 */
    const val SYS_VOID = "sys.void"
    /** 新增 `sys.violation`：校验器发现的不变量违反。 */
    const val SYS_VIOLATION = "sys.violation"
}

/** actor 的合法形态。空或未知前缀会被校验器标出来。 */
object EvActors {
    const val HOST = "host"
    const val CODE = "code"
    const val USER = "user"
    const val AGENT_PREFIX = "agent:"
}
