package org.cordis.api

import org.cordis.ServiceKey

/**
 * 日历：宿主独占的那一半「我的日程」。
 *
 * 为什么必须由宿主提供：日历读写走 `CalendarContract` + 运行时权限，两者都挂在 Android
 * `Context` 上，而插件拿不到 `Context`。这层只做**数据搬运与校验**，不做业务判断——
 * "该不该建这个日程"是插件的事，"这批字节写不写得进去"是这里的事。
 *
 * 三个刻意的取舍：
 *
 *  1. **时间是毫秒**（UTC epoch），不是格式化字符串。日历本身要时区，所以 [EventDraft.timezone]
 *     单独给；不给就用设备时区。让模型算时区是把它推下悬崖。
 *  2. **重复日程不拆开**：读到的 [CalendarEvent.recurring] 只标记它属于一个重复序列，
 *     更新/删除作用于**整个序列**（Android 的规则），这一点由调用方在文案里说清楚。
 *  3. **写入必须落到具体日历**：不指定就取主日历；写不了就抛，不做"先建一个本地日历"这种
 *     替用户做主的事。
 */
interface CalendarService {

    /** 这台设备上有哪些日历（账号、是否可见、能不能写）。 */
    suspend fun calendars(): List<CalendarInfo>

    /** 按时间窗与关键词查日程；[EventQuery.limit] 上限由实现夹住。 */
    suspend fun events(query: EventQuery): List<CalendarEvent>

    /** 单个日程；不存在返回 null。 */
    suspend fun event(id: Long): CalendarEvent?

    /** 建一个日程，返回新的 event id。 */
    suspend fun create(draft: EventDraft): Long

    /** 建一批（同一次事务），返回 id 列表；任何一个失败就整批回滚。 */
    suspend fun createAll(drafts: List<EventDraft>): List<Long>

    /** 改一个日程（[draft] 里为 null 的字段保持不变）；返回是否真的存在并改到了。 */
    suspend fun update(id: Long, draft: EventDraft): Boolean

    /** 删一个日程；重复日程删整条序列。返回是否删到了。 */
    suspend fun delete(id: Long): Boolean

    companion object {
        val Key = ServiceKey<CalendarService>("host/calendar")
    }
}

/** 一个日历。 */
data class CalendarInfo(
    val id: Long,
    /** 用户看到的日历名，例如"我的日程"。 */
    val name: String,
    /** 账号名，本地日历是 `LOCAL`。 */
    val account: String,
    /** 能不能往里写。 */
    val writable: Boolean,
    val visible: Boolean,
    /** `#RRGGBB`。 */
    val color: String,
)

/**
 * 时间窗 + 关键词。
 *
 * 必须给窗口：日历可以是十年的数据，无条件扫描既慢又吃掉模型上下文。
 * 关键词只匹配标题、描述与地点，不做分词——模糊匹配的语义留给调用方。
 */
data class EventQuery(
    val fromMillis: Long,
    val toMillis: Long,
    val keyword: String? = null,
    val limit: Int = 20,
)

/**
 * 一个日程。
 *
 * [startMillis] / [endMillis] 是 UTC 毫秒；[allDay] 为真时它们对齐到本地零点，
 * 与 Android 的 `CalendarContract` 语义一致。
 */
data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val calendarName: String,
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timezone: String,
    /** 属于重复序列（周会、生日这类）；更新/删除作用于整个序列。 */
    val recurring: Boolean,
    /** 相对开始时间的提醒分钟数，可能为空。 */
    val reminderMinutes: List<Int> = emptyList(),
)

/**
 * 要写入的日程。
 *
 * 除 [title] 与 [startMillis] 外都可省：不给结束时间按"开始 + 1 小时"，
 * 不给时区按设备时区，不给 [calendarId] 按主日历。
 */
data class EventDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long? = null,
    val allDay: Boolean = false,
    val calendarId: Long? = null,
    val description: String? = null,
    val location: String? = null,
    val timezone: String? = null,
    /** 提醒分钟数，例如 `listOf(10, 60)`；空表示不要提醒。 */
    val reminderMinutes: List<Int> = emptyList(),
)
