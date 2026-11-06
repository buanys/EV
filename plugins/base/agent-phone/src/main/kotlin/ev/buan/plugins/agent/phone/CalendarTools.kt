package ev.buan.plugins.agent.phone

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.CalendarEvent
import org.cordis.api.CalendarService
import org.cordis.api.EventDraft
import org.cordis.api.EventQuery
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日历工具的实现：参数校验 → 调用宿主契约 → 把结果拼成一段能直接喂给模型的 JSON。
 *
 * 三条规矩：
 *
 *  1. **参数错误就地返回原因**，不抛异常。工具抛出去只会在 agent 循环里变成一句
 *     "工具失败"，模型学不到"哪里写错了"。
 *  2. **写入后读回**。创建/修改都重新查一次，把读到的真实值交回——日历会自己补时区、
 *     调整全天事件的对齐，只回"成功"等于让模型相信一个它没见过的状态。
 *  3. **时间是 UTC 毫秒进出**，只有 `PhoneTime` 碰字符串，避免时区在两层里各算一遍。
 */
internal class CalendarTool(
    private val calendar: CalendarService,
    override val spec: ToolSpec,
) : Tool {

    override suspend fun invoke(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson.ifBlank { "{}" })
        when (spec.name) {
            "list_calendars" -> listCalendars()
            "search_calendar_events" -> searchEvents(args)
            "read_calendar_event" -> readEvent(args)
            "create_calendar_event" -> createEvent(args)
            "create_calendar_events" -> createEvents(args)
            "update_calendar_event" -> updateEvent(args)
            "delete_calendar_event" -> deleteEvent(args)
            else -> error("工具 ${spec.name} 没有实现")
        }
    }.getOrElse { error -> failure(error) }

    // ---------------------------------------------------------------- 读

    private suspend fun listCalendars(): String {
        val items = calendar.calendars()
        val array = JSONArray()
        items.forEach { info ->
            array.put(
                JSONObject()
                    .put("calendar_id", info.id)
                    .put("name", info.name)
                    .put("account", info.account)
                    .put("writable", info.writable)
                    .put("visible", info.visible)
                    .put("color", info.color),
            )
        }
        return JSONObject().put("items", array).put("count", items.size).toString()
    }

    private suspend fun searchEvents(args: JSONObject): String {
        val from = PhoneTime.parseInstant(args.optString("from")) ?: return badArgument("from 需要带偏移的 ISO 8601 时间")
        val to = PhoneTime.parseInstant(args.optString("to")) ?: return badArgument("to 需要带偏移的 ISO 8601 时间")
        if (to <= from) return badArgument("to 必须晚于 from")
        val query = EventQuery(
            fromMillis = from,
            toMillis = to,
            keyword = args.optString("keyword").takeIf { it.isNotBlank() },
            limit = args.optInt("limit", 20),
        )
        val events = calendar.events(query)
        return JSONObject()
            .put("items", JSONArray().also { array -> events.forEach { array.put(it.toJson()) } })
            .put("count", events.size)
            .put("window", JSONObject().put("from", PhoneTime.formatIso(from)).put("to", PhoneTime.formatIso(to)))
            .toString()
    }

    private suspend fun readEvent(args: JSONObject): String {
        val id = args.optLong("event_id", -1L)
        if (id <= 0) return badArgument("event_id 必须是搜索里拿到的原始数值 id")
        val event = calendar.event(id)
            ?: return JSONObject().put("found", false).put("event_id", id).toString()
        return JSONObject().put("found", true).put("event", event.toJson()).toString()
    }

    // ---------------------------------------------------------------- 写

    private suspend fun createEvent(args: JSONObject): String {
        val draft = args.toDraft() ?: return badArgument("title 与 start_time 必填，start_time 用带偏移的 ISO 8601")
        val id = calendar.create(draft)
        // 写完读回：时区与全天对齐由日历决定，只回"成功"等于让模型相信一个它没见过的状态。
        val saved = calendar.event(id)
        return JSONObject()
            .put("created", true)
            .put("event_id", id)
            .put("event", saved?.toJson() ?: JSONObject.NULL)
            .toString()
    }

    private suspend fun createEvents(args: JSONObject): String {
        val array = args.optJSONArray("events") ?: return badArgument("events 必须是一个非空数组")
        if (array.length() == 0) return badArgument("events 是空的")
        if (array.length() > MAX_BATCH) return badArgument("一次最多 $MAX_BATCH 条")
        val shared = args.optLong("calendar_id", 0L).takeIf { it > 0 }
        val drafts = mutableListOf<EventDraft>()
        for (index in 0 until array.length()) {
            val draft = array.optJSONObject(index)?.toDraft(shared)
                ?: return badArgument("events[$index] 缺少 title 或合法的 start_time")
            drafts += draft
        }
        val ids = calendar.createAll(drafts)
        val saved = ids.mapNotNull { calendar.event(it) }
        return JSONObject()
            .put("created", ids.size)
            .put("event_ids", JSONArray(ids))
            .put("items", JSONArray().also { out -> saved.forEach { out.put(it.toJson()) } })
            .toString()
    }

    private suspend fun updateEvent(args: JSONObject): String {
        val id = args.optLong("event_id", -1L)
        if (id <= 0) return badArgument("event_id 必须是搜索里拿到的原始数值 id")
        val draft = args.toDraft() ?: return badArgument("title、start_time、end_time 都必须给")
        val changed = calendar.update(id, draft)
        val saved = if (changed) calendar.event(id) else null
        return JSONObject()
            .put("updated", changed)
            .put("event_id", id)
            .put("event", saved?.toJson() ?: JSONObject.NULL)
            .toString()
    }

    private suspend fun deleteEvent(args: JSONObject): String {
        val id = args.optLong("event_id", -1L)
        if (id <= 0) return badArgument("event_id 必须是搜索里拿到的原始数值 id")
        val changed = calendar.delete(id)
        return JSONObject().put("deleted", changed).put("event_id", id).toString()
    }

    // ---------------------------------------------------------------- 辅助

    private fun CalendarEvent.toJson(): JSONObject = JSONObject()
        .put("event_id", id)
        .put("calendar_id", calendarId)
        .put("calendar", calendarName)
        .put("title", title)
        .put("start", PhoneTime.formatIso(startMillis))
        .put("end", PhoneTime.formatIso(endMillis))
        .put("start_local", PhoneTime.formatInstant(startMillis))
        .put("all_day", allDay)
        .put("timezone", timezone)
        .put("recurring", recurring)
        .put("location", location)
        .put("description", description.take(MAX_DESCRIPTION))
        .put("reminder_minutes", JSONArray(reminderMinutes))

    private fun JSONObject.toDraft(sharedCalendar: Long? = null): EventDraft? {
        val title = optString("title").trim().ifEmpty { return null }
        val start = PhoneTime.parseInstant(optString("start_time")) ?: return null
        val end = if (has("end_time") && optString("end_time").isNotBlank()) {
            PhoneTime.parseInstant(optString("end_time")) ?: return null
        } else {
            PhoneTime.defaultEnd(start)
        }
        if (end < start) return null
        val calendarId = optLong("calendar_id", 0L).takeIf { it > 0 } ?: sharedCalendar
        return EventDraft(
            title = title.take(MAX_TITLE),
            startMillis = start,
            endMillis = end,
            allDay = optBoolean("all_day", false),
            calendarId = calendarId,
            description = optString("description").takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION),
            location = optString("location").takeIf { it.isNotBlank() }?.take(MAX_TITLE),
            reminderMinutes = optJSONArray("reminder_minutes")?.let { array ->
                buildList { for (i in 0 until array.length()) add(array.optInt(i)) }.filter { it >= 0 }
            }.orEmpty(),
        )
    }

    private fun badArgument(reason: String): String =
        JSONObject().put("ok", false).put("error", "INVALID_ARGUMENT").put("message", reason).toString()

    /** 失败也要给出**能照做**的那句话：授权缺失说清楚去哪授权，其余给原始异常。 */
    private fun failure(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .map { it.message.orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: error.javaClass.simpleName
        val code = when (error) {
            is SecurityException -> "PERMISSION_DENIED"
            is IllegalStateException -> "CALENDAR_UNAVAILABLE"
            else -> "FAILED"
        }
        return JSONObject().put("ok", false).put("error", code).put("message", message).toString()
    }

    internal companion object {
        /** 一个 spec 一个实例：`Tool` 的身份就是它的 spec，绑定在构造时定死。 */
        fun all(calendar: CalendarService): List<CalendarTool> =
            CalendarToolSpecs.ALL.map { CalendarTool(calendar, it) }


        const val MAX_BATCH = 30
        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 8000
    }
}
