package ev.buan.plugins.agent.phone

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时间的解析与格式化是这组工具唯一"看起来简单、错了很难发现"的部分。
 *
 * 每条用例都钉一个具体的坑：带偏移的能进、纯日期按当天零点、设备时区与 UTC 不能混。
 */
class PhoneTimeTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `带偏移的 ISO 时间解析成 UTC 毫秒`() {
        // 2026-11-07 09:00 +08:00 == 2026-11-07 01:00 UTC
        val millis = PhoneTime.parseInstant("2026-11-07T09:00:00+08:00")
        assertNotNull(millis)
        assertEquals("2026-11-07T01:00:00Z", java.time.Instant.ofEpochMilli(millis!!).toString())
    }

    @Test
    fun `纯日期按设备时区的当天零点`() {
        val millis = PhoneTime.parseInstant("2026-11-07", shanghai)
        assertNotNull(millis)
        assertEquals("2026-11-06T16:00:00Z", java.time.Instant.ofEpochMilli(millis!!).toString())
    }

    @Test
    fun `没有偏移的本地时间按给定时区解释`() {
        val millis = PhoneTime.parseInstant("2026-11-07T09:00:00", shanghai)
        assertNotNull(millis)
        assertEquals("2026-11-07T01:00:00Z", java.time.Instant.ofEpochMilli(millis!!).toString())
    }

    @Test
    fun `写坏的时间返回 null 而不是抛`() {
        assertNull(PhoneTime.parseInstant("明天早上九点"))
        assertNull(PhoneTime.parseInstant(""))
    }

    @Test
    fun `格式化回来的是设备时区的人话`() {
        val millis = PhoneTime.parseInstant("2026-11-07T09:00:00+08:00")!!
        assertEquals("2026-11-07 09:00", PhoneTime.formatInstant(millis, shanghai))
    }

    @Test
    fun `ISO 输出可以原样再解析回去`() {
        val millis = PhoneTime.parseInstant("2026-11-07T09:00:00+08:00")!!
        val iso = PhoneTime.formatIso(millis, shanghai)
        assertEquals(millis, PhoneTime.parseInstant(iso))
    }

    @Test
    fun `不给结束时间按一小时`() {
        val start = PhoneTime.parseInstant("2026-11-07T09:00:00+08:00")!!
        assertEquals(start + 3_600_000L, PhoneTime.defaultEnd(start))
    }
}
