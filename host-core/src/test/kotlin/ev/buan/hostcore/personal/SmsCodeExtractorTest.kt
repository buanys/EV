package ev.buan.hostcore.personal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 验证码识别的两个失败模式，一个都不能漏：
 * **漏认**（用户在等，模型说"没有验证码"）和**错认**（模型拿订单号去登录）。
 */
class SmsCodeExtractorTest {

    @Test
    fun `典型验证码短信`() {
        assertEquals("123456", SmsCodeExtractor.extract("【某银行】您的验证码是 123456，5 分钟内有效。"))
        assertEquals("8888", SmsCodeExtractor.extract("验证码 8888"))
    }

    @Test
    fun `英文短信同样认`() {
        assertEquals("4321", SmsCodeExtractor.extract("Your verification code is 4321"))
    }

    @Test
    fun `11 位手机号不能被当成验证码`() {
        assertNull(SmsCodeExtractor.extract("请联系 13800138000 办理"))
    }

    @Test
    fun `长数字串里不截取子串`() {
        // 12 位订单号：既不该整体命中，也不该从中间切出 8 位
        assertNull(SmsCodeExtractor.extract("订单号 123456789012 已发货"))
    }

    @Test
    fun `与中文相邻的数字仍然能命中`() {
        // 这里正是 \b 会失效的地方
        assertEquals("1234567", SmsCodeExtractor.extract("订单号1234567请查收"))
    }

    @Test
    fun `太短的数字不算`() {
        assertNull(SmsCodeExtractor.extract("你有 3 条新消息"))
        assertNull(SmsCodeExtractor.extract("余额 12 元"))
    }

    @Test
    fun `九位以上不算`() {
        assertNull(SmsCodeExtractor.extract("流水号 123456789"))
    }

    @Test
    fun `关键词优先于更晚到的裸数字短信`() {
        val bodies = listOf(
            "您有一笔 654321 元的转账待确认",
            "【某平台】验证码 246810，请勿泄露",
        )
        assertEquals("246810", SmsCodeExtractor.pick(bodies))
    }

    @Test
    fun `都没有关键词时按传入顺序取第一个`() {
        assertEquals("111111", SmsCodeExtractor.pick(listOf("111111", "222222")))
    }

    @Test
    fun `一条候选都没有时返回 null`() {
        assertNull(SmsCodeExtractor.pick(listOf("你好", "在吗")))
        assertNull(SmsCodeExtractor.pick(emptyList()))
    }
}
