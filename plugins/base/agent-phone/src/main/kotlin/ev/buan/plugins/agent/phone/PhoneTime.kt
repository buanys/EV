package ev.buan.plugins.agent.phone

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间与日程的**纯**格式化/解析：没有 Android、没有 JSON，所以能一条条单测。
 *
 * 这里承担的是整个插件最容易出错的那部分：模型给的是人话里的时间（"明天九点"它自己算好的
 * ISO 串），日历要的是 UTC 毫秒。中间只要悄悄用了 UTC 或设备时区之一，用户就会看到
 * "明明说的是九点，怎么显示八点"——而且没人会怀疑到这一层。
 */
internal object PhoneTime {

    /** 模型给的 ISO 8601（带偏移）→ UTC 毫秒；也接受纯日期（按当天零点）。 */
    fun parseInstant(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant().toEpochMilli() }
        runCatching {
            return java.time.LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /** UTC 毫秒 → 设备时区的 `yyyy-MM-dd HH:mm`，给模型和用户看。 */
    fun formatInstant(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(zone)
            .format(Instant.ofEpochMilli(millis))

    /** UTC 毫秒 → 带偏移的 ISO 8601，给程序用（也方便模型原样传回来）。 */
    fun formatIso(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** 一小时，跨天也成立；全天事件不适用。 */
    fun defaultEnd(startMillis: Long): Long = startMillis + 60L * 60L * 1000L
}
