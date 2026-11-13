package org.cordis.api

import org.cordis.ServiceKey

/**
 * 一条短信。
 *
 * [receivedAt] 是毫秒时间戳；[inbound] 区分收件箱与已发送——
 * "他回我了吗"和"我发了吗"是两个不同的问题，混在一条流里会答错。
 */
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val receivedAt: Long,
    val inbound: Boolean,
)

/**
 * 从短信里提出来的验证码。
 *
 * 单独一个类型而不是让调用方自己正则：验证码的判定规则（多长、要不要看关键词、
 * 怎么排除手机号）是这个插件的经验，调用方不该各写一遍还写得不一样。
 */
data class SmsCode(
    val code: String,
    val address: String,
    val receivedAt: Long,
)

/**
 * 读取短信。**只读**。
 *
 * 收发短信要 `SEND_SMS` / `WRITE_SMS`，宿主没有声明，所以这里只有读。
 *
 * 声明 `SMS` 才拿得到绑定；未授权时每次调用抛 `SecurityException`。
 */
interface SmsService {

    /** 最近 [limit] 条（收件 + 已发送），按时间倒序。 */
    suspend fun recent(limit: Int = 20): List<SmsMessage>

    /** 正文模糊匹配 [query]，按时间倒序。 */
    suspend fun search(query: String, limit: Int = 20): List<SmsMessage>

    /**
     * 最近 [maxAgeMinutes] 分钟内收到的验证码，没有就是 null。
     *
     * 只认 4–8 位连续数字：11 位手机号不会被误当验证码。
     * 命中关键词（"验证码"、"code"、"verification"）的优先，其次才是裸数字串。
     */
    suspend fun latestCode(maxAgeMinutes: Int = 10): SmsCode?

    companion object {
        val Key = ServiceKey<SmsService>("host/sms")
    }
}
