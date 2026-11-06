package ev.buan.plugins.agent.phone

import ev.buan.plugins.agent.api.ToolSpec

/**
 * 日历的七个工具。
 *
 * 描述里刻意写清三件模型最容易搞错的事：时间带偏移、`event_id` 是原始 id（不是搜索结果里的
 * 序号）、重复日程改动作用于整个序列。写清楚了模型就少一轮试错，写含糊了它就会先猜后撞。
 */
internal object CalendarToolSpecs {

    private const val READ_ONLY = """{"type":"object","properties":{"""

    val LIST_CALENDARS = ToolSpec(
        name = "list_calendars",
        description = "列出这台设备上的日历（账号、颜色、是否可写）。建日程前先看一眼哪个可写。",
        parametersJson = READ_ONLY + """},"additionalProperties":false}""",
    )

    val SEARCH_EVENTS = ToolSpec(
        name = "search_calendar_events",
        description = "按时间窗查日程，重复日程会被展开成具体实例。必须给时间窗：日历可以是十年的数据。" +
            "时间为带偏移的 ISO 8601（如 2026-11-07T09:00:00+08:00）。",
        parametersJson = READ_ONLY.replace("{", """{"required":["from","to"],""") +
            ""","from":{"type":"string","description":"起始时间，带偏移的 ISO 8601"},""" +
            """"to":{"type":"string","description":"结束时间，带偏移的 ISO 8601"},""" +
            """"keyword":{"type":"string","description":"只匹配标题、描述与地点"},""" +
            """"limit":{"type":"integer","minimum":1,"maximum":50,"default":20}},""" +
            """"additionalProperties":false}""",
    )

    val READ_EVENT = ToolSpec(
        name = "read_calendar_event",
        description = "按原始 event_id 读一个日程（含提醒）。event_id 来自搜索结果，不是行号。",
        parametersJson = """"{"type":"object","properties":{"event_id":{"type":"integer","description":"原始 event id"}},""" +
            """"required":["event_id"],"additionalProperties":false}""",
    )

    val CREATE_EVENT = ToolSpec(
        name = "create_calendar_event",
        description = "创建一个日程和提醒，写完读回确认。一次性的用起止时间；不给结束时间按一小时。" +
            "全天事件用当天的零点到次日零点。超时后先查询，不要重复创建。",
        parametersJson = """{"type":"object","properties":{""" +
            """"title":{"type":"string","description":"标题"},""" +
            """"start_time":{"type":"string","description":"带偏移的 ISO 8601"},""" +
            """"end_time":{"type":"string","description":"带偏移的 ISO 8601；省略按开始后一小时"},""" +
            """"all_day":{"type":"boolean","default":false},""" +
            """"calendar_id":{"type":"integer","description":"省略则用主日历"},""" +
            """"description":{"type":"string"},"location":{"type":"string"},""" +
            """"reminder_minutes":{"type":"array","items":{"type":"integer","minimum":0},"description":"提前多少分钟提醒"}},""" +
            """"required":["title","start_time"],"additionalProperties":false}""",
        dangerous = true,
    )

    val CREATE_EVENTS = ToolSpec(
        name = "create_calendar_events",
        description = "一次创建多条日程（最多 30 条），全部校验通过才写；中途失败会撤掉已建的。" +
            "结果未确认时先查询，不要重复创建。",
        parametersJson = """{"type":"object","properties":{""" +
            """"calendar_id":{"type":"integer","description":"省略则用主日历"},""" +
            """"events":{"type":"array","minItems":1,"maxItems":30,"items":{"type":"object","properties":{""" +
            """"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},""" +
            """"all_day":{"type":"boolean"},"description":{"type":"string"},"location":{"type":"string"},""" +
            """"reminder_minutes":{"type":"array","items":{"type":"integer"}},""" +
            """"required":["title","start_time"],"additionalProperties":false}}},""" +
            """"required":["events"],"additionalProperties":false}""",
        dangerous = true,
    )

    val UPDATE_EVENT = ToolSpec(
        name = "update_calendar_event",
        description = "按原始 event_id 改日程：标题与起止时间必给（改动时间要同时给两端），其余可选。" +
            "重复日程改动作用于整个序列。",
        parametersJson = """{"type":"object","properties":{""" +
            """"event_id":{"type":"integer","description":"原始 event id"},""" +
            """"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},""" +
            """"all_day":{"type":"boolean"},"description":{"type":"string"},"location":{"type":"string"},""" +
            """"reminder_minutes":{"type":"array","items":{"type":"integer"}}},""" +
            """"required":["event_id","title","start_time","end_time"],"additionalProperties":false}""",
        dangerous = true,
    )

    val DELETE_EVENT = ToolSpec(
        name = "delete_calendar_event",
        description = "按原始 event_id 删日程；重复日程删整条序列。删之前先读一次确认是哪一个。",
        parametersJson = """"{"type":"object","properties":{"event_id":{"type":"integer","description":"原始 event id"}},""" +
            """"required":["event_id"],"additionalProperties":false}""",
        dangerous = true,
    )

    val ALL = listOf(LIST_CALENDARS, SEARCH_EVENTS, READ_EVENT, CREATE_EVENT, CREATE_EVENTS, UPDATE_EVENT, DELETE_EVENT)
}
