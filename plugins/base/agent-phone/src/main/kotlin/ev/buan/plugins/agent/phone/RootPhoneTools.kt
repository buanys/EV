package ev.buan.plugins.agent.phone

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 闹钟与便签：都经 root 访问厂商 provider（见 [RootContent] 的原因）。
 *
 * **便签只有读**：系统便签的写入接口要求调用方带一个内部契约（`text_note` 上带
 * `caller_package` 与回填参数的查询串），第三方应用用 `content insert` 送进去会被静默丢弃
 * （真机实测：29 条便签、插入后仍是 29 条、返回空）。与其做一个"说成功其实没写"的工具，
 * 不如只给能确认的那一半——搜索与读取。写便签要等 root 助手进程那一层。
 */
internal object RootPhoneTools {

    private const val ALARM_URI = "content://com.coloros.alarmclock.alarmclock/alarm"
    private const val NOTE_URI = "content://com.nearme.note/rich_notes"

    /** 一台设备上最多报多少条闹钟：闹钟不会太多，但坏数据可能很多。 */
    private const val MAX_ALARMS = 50

    fun all(): List<Tool> = listOf(
        tool(LIST_ALARMS) { args -> listAlarms(args) },
        tool(SET_ALARM) { args -> setAlarm(args) },
        tool(UPDATE_ALARM) { args -> updateAlarm(args) },
        tool(SET_ALARM_ENABLED) { args -> enableAlarm(args) },
        tool(DELETE_ALARM) { args -> deleteAlarm(args) },
        tool(SEARCH_NOTES) { args -> searchNotes(args) },
        tool(READ_NOTE) { args -> readNote(args) },
    )

    // ---------------------------------------------------------------- 闹钟

    private fun listAlarms(args: JSONObject): String {
        val rows = RootContent.query(ALARM_URI)
            ?: return failed("CLOCK_UNAVAILABLE", "读不到闹钟：这条通路需要 root（su）授权")
        val enabledOnly = args.optBoolean("enabled_only", true)
        val keyword = args.optString("query").trim().takeIf { it.isNotEmpty() }
        val alarms = rows.mapNotNull { row -> row.toAlarm() }
            .filter { !enabledOnly || it.getBoolean("enabled") }
            .filter { keyword == null || it.optString("label").contains(keyword, ignoreCase = true) }
            .sortedWith(compareBy({ it.getInt("hour") }, { it.getInt("minute") }))
            .take(MAX_ALARMS)
        return JSONObject().put("items", JSONArray(alarms)).put("count", alarms.size).toString()
    }

    private fun setAlarm(args: JSONObject): String {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return invalid("hour 必须是 0-23，minute 必须是 0-59")
        }
        val days = args.optJSONArray("days")?.let { array ->
            buildList { for (i in 0 until array.length()) add(array.optInt(i)) }
        }.orEmpty()
        val mask = days.fold(0) { acc, day -> acc or (1 shl (day.coerceIn(0, 6))) }
        val values: Map<String, Any> = mapOf(
            "hour" to hour,
            "minutes" to minute,
            "enabled" to 1,
            "daysofweek" to mask,
            "message" to args.optString("label").ifBlank { "闹钟" }.take(100),
        )
        // 先记下现有的 id：插入之后"多出来的那一条"才是我们建的，按时间/标签去猜会撞上旧闹钟。
        val before = RootContent.query(ALARM_URI)?.mapNotNull { it["_id"] }?.toSet().orEmpty()
        val inserted = RootContent.insert(ALARM_URI, values)
            ?: return failed("CLOCK_WRITE_FAILED", "系统时钟没有接受这次新建（需要 root）")
        val created = RootContent.query(ALARM_URI)
            ?.mapNotNull { row -> row.toAlarm()?.takeIf { it.optString("alarm_id") !in before } }
            ?.firstOrNull()
        return JSONObject()
            .put("created", created != null)
            .put("alarm", created ?: JSONObject.NULL)
            .put("raw", inserted.trim().take(200))
            .toString()
    }

    private fun updateAlarm(args: JSONObject): String {
        val id = args.optString("alarm_id").trim()
            .ifEmpty { args.optInt("alarm_id", -1).takeIf { it > 0 }?.toString().orEmpty() }
        if (id.isEmpty()) return invalid("alarm_id 必填，取自 list_alarms")
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return invalid("hour 0-23，minute 0-59，两者都要给")
        val ok = RootContent.update(ALARM_URI, mapOf("hour" to hour, "minutes" to minute), "_id = $id")
        if (!ok) return failed("CLOCK_WRITE_FAILED", "系统时钟没有接受这次修改（需要 root，且 alarm_id 要存在）")
        val after = RootContent.query(ALARM_URI)?.mapNotNull { it.toAlarm() }
            ?.firstOrNull { it.optString("alarm_id") == id }
        return JSONObject().put("updated", after != null).put("alarm", after ?: JSONObject.NULL).toString()
    }

    private fun enableAlarm(args: JSONObject): String {
        val id = args.optString("alarm_id").trim()
        if (id.isEmpty()) return invalid("alarm_id 必填")
        val enabled = args.optBoolean("enabled", true)
        val ok = RootContent.update(ALARM_URI, mapOf("enabled" to if (enabled) 1 else 0), "_id = $id")
        if (!ok) return failed("CLOCK_WRITE_FAILED", "系统时钟没有接受这次开关（需要 root）")
        val after = RootContent.query(ALARM_URI)?.mapNotNull { it.toAlarm() }
            ?.firstOrNull { it.optString("alarm_id") == id }
        return JSONObject()
            .put("updated", after?.optBoolean("enabled") == enabled)
            .put("alarm", after ?: JSONObject.NULL)
            .toString()
    }

    private fun deleteAlarm(args: JSONObject): String {
        val id = args.optString("alarm_id").trim()
        if (id.isEmpty()) return invalid("alarm_id 必填")
        val ok = RootContent.delete(ALARM_URI, "_id = $id")
        if (!ok) return failed("CLOCK_WRITE_FAILED", "系统时钟没有接受这次删除（需要 root）")
        val stillThere = RootContent.query(ALARM_URI)?.any { it["_id"] == id } ?: false
        return JSONObject().put("deleted", !stillThere).put("alarm_id", id).toString()
    }

    private fun Map<String, String?>.toAlarm(): JSONObject? {
        val hour = this["hour"]?.toIntOrNull() ?: return null
        val minute = this["minutes"]?.toIntOrNull() ?: this["minute"]?.toIntOrNull() ?: return null
        val mask = this["daysofweek"]?.toIntOrNull() ?: 0
        return JSONObject()
            .put("alarm_id", this["_id"] ?: return null)
            .put("hour", hour)
            .put("minute", minute)
            .put("enabled", this["enabled"] == "1")
            .put("label", this["message"].orEmpty())
            .put("days", JSONArray(daysOf(mask)))
            .put("snooze_minutes", this["snooze"]?.toIntOrNull() ?: 0)
    }

    /** 位掩码 → `["mon", ... ]`；`127` 是每天。 */
    private fun daysOf(mask: Int): List<String> =
        listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").filterIndexed { day, _ ->
            mask and (1 shl day) != 0
        }

    // ---------------------------------------------------------------- 便签

    private fun searchNotes(args: JSONObject): String {
        val keyword = args.optString("query").trim()
        if (keyword.isEmpty()) return invalid("query 必填（只在标题里匹配，正文太长不适合全表扫）")
        val escaped = keyword.replace("'", "''")
        val rows = RootContent.query(
            NOTE_URI,
            projection = listOf("local_id", "raw_title", "update_time"),
            selection = "raw_title LIKE '%$escaped%' AND deleted = 0",
        ) ?: return failed("NOTES_UNAVAILABLE", "读不到便签：这条通路需要 root（su）授权")
        val items = rows.mapNotNull { row ->
            val id = row["local_id"] ?: return@mapNotNull null
            JSONObject()
                .put("note_id", id)
                .put("title", row["raw_title"].orEmpty())
                .put("updated_at", row["update_time"]?.toLongOrNull() ?: 0L)
        }
        return JSONObject().put("items", JSONArray(items)).put("count", items.size).toString()
    }

    private fun readNote(args: JSONObject): String {
        val id = args.optString("note_id").trim()
        if (id.isEmpty()) return invalid("note_id 必填，取自 search_notes")
        val escaped = id.replace("'", "''")
        // 正文单列查询：便签正文里有逗号和 `key=` 形状，走通用的行解析会被切断。
        val body = RootContent.query(
            NOTE_URI,
            projection = listOf("raw_text"),
            selection = "local_id = '$escaped'",
        )?.firstOrNull()?.get("raw_text")
        if (body == null) {
            val exists = RootContent.query(
                NOTE_URI,
                projection = listOf("local_id"),
                selection = "local_id = '$escaped'",
            )?.isNotEmpty() == true
            return JSONObject().put("found", exists).put("note_id", id).toString()
        }
        val title = RootContent.query(
            NOTE_URI,
            projection = listOf("raw_title"),
            selection = "local_id = '$escaped'",
        )?.firstOrNull()?.get("raw_title").orEmpty()
        return JSONObject()
            .put("found", true)
            .put("note_id", id)
            .put("title", title)
            .put("content", body.take(MAX_NOTE_CHARS))
            .toString()
    }

    // ---------------------------------------------------------------- 规格

    private fun tool(spec: ToolSpec, block: suspend (JSONObject) -> String): Tool = object : Tool {
        override val spec = spec
        override suspend fun invoke(argumentsJson: String): String = try {
            block(JSONObject(argumentsJson.ifBlank { "{}" }))
        } catch (error: Throwable) {
            failed("FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun invalid(reason: String) =
        JSONObject().put("ok", false).put("error", "INVALID_ARGUMENT").put("message", reason).toString()

    private fun failed(code: String, reason: String) =
        JSONObject().put("ok", false).put("error", code).put("message", reason).toString()

    private const val MAX_NOTE_CHARS = 20_000

    private val ALARM_ARGS = """"alarm_id":{"type":"string","description":"闹钟 id，取自 list_alarms"}"""

    private val LIST_ALARMS = ToolSpec(
        name = "list_alarms",
        description = "列出系统时钟里的闹钟（时间、开关、重复日、标签）。需要 root。",
        parametersJson = """{"type":"object","properties":{""" +
            """"enabled_only":{"type":"boolean","default":true},""" +
            """"query":{"type":"string","description":"按标签过滤"}},"additionalProperties":false}""",
    )

    private val SET_ALARM = ToolSpec(
        name = "set_alarm",
        description = "在系统时钟里建一个闹钟并读回确认。days 用 0=周一 … 6=周日 的下标数组，" +
            "省略表示只响一次。需要 root。",
        parametersJson = """{"type":"object","properties":{""" +
            """"hour":{"type":"integer","minimum":0,"maximum":23},""" +
            """"minute":{"type":"integer","minimum":0,"maximum":59},""" +
            """"label":{"type":"string","description":"闹钟标签"},""" +
            """"days":{"type":"array","items":{"type":"integer","minimum":0,"maximum":6},"description":"0=周一 … 6=周日"}},""" +
            """"required":["hour","minute"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val UPDATE_ALARM = ToolSpec(
        name = "update_alarm_time",
        description = "改一个闹钟的时间（保留原来的开关状态），改完读回确认。需要 root。",
        parametersJson = """{"type":"object","properties":{$ALARM_ARGS,""" +
            """"hour":{"type":"integer","minimum":0,"maximum":23},""" +
            """"minute":{"type":"integer","minimum":0,"maximum":59}},""" +
            """"required":["alarm_id","hour","minute"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SET_ALARM_ENABLED = ToolSpec(
        name = "set_alarm_enabled",
        description = "打开或关闭一个闹钟并读回确认。需要 root。",
        parametersJson = """{"type":"object","properties":{$ALARM_ARGS,""" +
            """"enabled":{"type":"boolean"}},""" +
            """"required":["alarm_id","enabled"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val DELETE_ALARM = ToolSpec(
        name = "delete_alarm",
        description = "删掉一个闹钟并确认它真的不在了。需要 root。",
        parametersJson = """{"type":"object","properties":{$ALARM_ARGS},""" +
            """"required":["alarm_id"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SEARCH_NOTES = ToolSpec(
        name = "search_notes",
        description = "在系统便签里按标题关键词找（返回 id、标题、更新时间）。只读，需要 root。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"标题关键词"}},""" +
            """"required":["query"],"additionalProperties":false}""",
    )

    private val READ_NOTE = ToolSpec(
        name = "read_note",
        description = "按 note_id 读一条系统便签的正文。只读，需要 root。",
        parametersJson = """{"type":"object","properties":{"note_id":{"type":"string","description":"便签 id，取自 search_notes"}},""" +
            """"required":["note_id"],"additionalProperties":false}""",
    )
}
