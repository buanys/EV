package ev.buan

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.CalendarEvent
import org.cordis.api.CalendarInfo
import org.cordis.api.CalendarService
import org.cordis.api.EventDraft
import org.cordis.api.EventQuery
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService

/**
 * `CalendarContract` 的实现：宿主侧的日历读写。
 *
 * 四件必须做对的事：
 *
 *  1. **读用 `Instances`，不用 `Events`**。`Events` 里一行是「序列」，每周的例会只有一条
 *     `DTSTART` 在去年；想知道"这周有什么会"必须让系统把重复展开，`Instances` 就是展开后的视图。
 *  2. **写的目标是具体日历**。不给 calendar id 就取主日历；一个可写日历都没有就抛，
 *     绝不替用户新建本地日历。
 *  3. **提醒是另一张表**（`Reminders`），建完事件再补，删的时候要一起收拾。
 *  4. **每次调用都复核授权**：授权被撤销后，已经拿到实例的插件不该还能继续用（与凭据同一规则）。
 */
internal class CalendarServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : CalendarService {

    private val resolver get() = context.contentResolver

    private fun require() {
        if (permission != null && !permission.isGranted(HostPermission.CALENDAR)) {
            throw SecurityException("缺少权限 CALENDAR（在插件管理页授予后重试）")
        }
    }

    override suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        require()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarInfo(
                            id = cursor.getLong(0),
                            name = cursor.getString(1).orEmpty(),
                            account = cursor.getString(2).orEmpty(),
                            writable = cursor.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                            visible = cursor.getInt(4) == 1,
                            color = String.format("#%06X", 0xFFFFFF and cursor.getInt(5)),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override suspend fun events(query: EventQuery): List<CalendarEvent> = withContext(Dispatchers.IO) {
        require()
        val limit = query.limit.coerceIn(1, MAX_ROWS)
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, query.fromMillis)
        ContentUris.appendId(builder, query.toMillis)
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val selection = if (keyword == null) null else
            "(${CalendarContract.Instances.TITLE} LIKE ? OR ${CalendarContract.Instances.DESCRIPTION} LIKE ?" +
                " OR ${CalendarContract.Instances.EVENT_LOCATION} LIKE ?)"
        val args = if (keyword == null) null else arrayOf("%$keyword%", "%$keyword%", "%$keyword%")
        resolver.query(
            builder.build(),
            INSTANCE_PROJECTION,
            selection,
            args,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList { while (size < limit && cursor.moveToNext()) add(readInstance(cursor)) }
        }.orEmpty()
    }

    override suspend fun event(id: Long): CalendarEvent? = withContext(Dispatchers.IO) {
        require()
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            EVENT_PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            readEvent(cursor).copy(reminderMinutes = reminders(id))
        }
    }

    override suspend fun create(draft: EventDraft): Long = withContext(Dispatchers.IO) {
        require()
        val target = draft.calendarId ?: primaryWritableCalendar()
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, draft.toValues(target))
            ?: throw IllegalStateException("日历拒绝了这次写入（检查标题与时间是否合法）")
        val id = uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("日历没有返回事件 id")
        writeReminders(id, draft.reminderMinutes)
        id
    }

    override suspend fun createAll(drafts: List<EventDraft>): List<Long> = withContext(Dispatchers.IO) {
        require()
        if (drafts.isEmpty()) return@withContext emptyList()
        // 不追求单事务：Android 没有跨包事务的公开入口。失败就把已建的删掉，
        // 让"整批"对调用方仍然是原子的——差别只在于崩溃时可能剩下几条（已如实记在 ADR 里）。
        val created = mutableListOf<Long>()
        try {
            drafts.forEach { created += create(it) }
            created.toList()
        } catch (error: Throwable) {
            created.forEach { runCatching { delete(it) } }
            throw error
        }
    }

    override suspend fun update(id: Long, draft: EventDraft): Boolean = withContext(Dispatchers.IO) {
        require()
        val values = ContentValues()
        values.put(CalendarContract.Events.TITLE, draft.title)
        values.put(CalendarContract.Events.DTSTART, draft.startMillis)
        draft.endMillis?.let { values.put(CalendarContract.Events.DTEND, it) }
        draft.description?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        draft.location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        draft.timezone?.let { values.put(CalendarContract.Events.EVENT_TIMEZONE, it) }
        values.put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
        val changed = resolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            values,
            null,
            null,
        )
        if (draft.reminderMinutes.isNotEmpty()) writeReminders(id, draft.reminderMinutes)
        changed > 0
    }

    override suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        require()
        resolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            null,
            null,
        ) > 0
    }

    // ---------------------------------------------------------------- 内部

    private fun EventDraft.toValues(calendarId: Long) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis ?: (startMillis + DEFAULT_DURATION_MS))
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, timezone ?: TimeZone.getDefault().id)
        description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        if (reminderMinutes.isNotEmpty()) put(CalendarContract.Events.HAS_ALARM, 1)
    }

    /**
     * 没指定日历时的落点：主日历优先，其次任意一个可写的。
     *
     * 用 `IS_PRIMARY DESC` 排序而不是自己挑：主日历由系统维护，Android 的约定就是这一列。
     */
    private suspend fun primaryWritableCalendar(): Long = withContext(Dispatchers.IO) {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    } ?: throw IllegalStateException("没有可写的日历：先在日历应用里建一个账号或本地日历")

    private fun writeReminders(eventId: Long, minutes: List<Int>) {
        if (minutes.isEmpty()) return
        resolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
        )
        minutes.forEach { minute ->
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, values) }
        }
    }

    private fun reminders(eventId: Long): List<Int> = runCatching {
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }.orEmpty()
    }.getOrElse { emptyList() }

    private fun readInstance(cursor: android.database.Cursor) = CalendarEvent(
        id = cursor.getLong(0),
        calendarId = cursor.getLong(1),
        calendarName = cursor.getString(2).orEmpty(),
        title = cursor.getString(3).orEmpty(),
        description = cursor.getString(4).orEmpty(),
        location = cursor.getString(5).orEmpty(),
        startMillis = cursor.getLong(6),
        endMillis = cursor.getLong(7),
        allDay = cursor.getInt(8) == 1,
        timezone = cursor.getString(9).orEmpty(),
        recurring = !cursor.getString(10).isNullOrEmpty(),
    )

    private fun readEvent(cursor: android.database.Cursor) = CalendarEvent(
        id = cursor.getLong(0),
        calendarId = cursor.getLong(1),
        calendarName = "",
        title = cursor.getString(2).orEmpty(),
        description = cursor.getString(3).orEmpty(),
        location = cursor.getString(4).orEmpty(),
        startMillis = cursor.getLong(5),
        endMillis = cursor.getLong(6),
        allDay = cursor.getInt(7) == 1,
        timezone = cursor.getString(8).orEmpty(),
        recurring = !cursor.getString(9).isNullOrEmpty(),
    )

    private companion object {
        /** 一次查询最多回多少行：日历可以是十年的数据，模型上下文不是。 */
        const val MAX_ROWS = 50
        const val DEFAULT_DURATION_MS = 60L * 60L * 1000L

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.RRULE,
        )

        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
        )
    }
}
