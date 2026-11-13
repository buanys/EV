package ev.buan

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import ev.buan.hostcore.personal.SmsCodeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.api.SmsCode
import org.cordis.api.SmsMessage
import org.cordis.api.SmsService

/**
 * `Telephony.Sms` 的实现：宿主侧的短信读取与验证码提取。
 *
 * 两件必须做对的事：
 *
 *  1. **只认收件箱**。已发送的消息里也可能有"验证码"这几个字（比如自己转发给谁），
 *     把它当验证码会给模型一个早就用过的数字。
 *  2. **4–8 位连续数字，且两侧不是数字**。11 位手机号、身份证号、订单号都不能混进来；
 *     正则用前后断言而不是 `\b`，因为 `\b` 对中文与数字相邻的情况判断不稳。
 *
 * 只读是硬边界：宿主没有声明 `SEND_SMS` / `WRITE_SMS`。
 */
internal class SmsServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : SmsService {

    private val resolver get() = context.contentResolver

    private fun require() {
        if (permission != null && !permission.isGranted(HostPermission.SMS)) {
            throw SecurityException("缺少权限 SMS（在插件管理页授予后重试）")
        }
    }

    override suspend fun recent(limit: Int): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            require()
            query(selection = null, args = null, limit = limit)
        }

    override suspend fun search(query: String, limit: Int): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            require()
            query(
                selection = "${Telephony.Sms.BODY} LIKE ?",
                args = arrayOf("%${query.trim()}%"),
                limit = limit,
            )
        }

    override suspend fun latestCode(maxAgeMinutes: Int): SmsCode? =
        withContext(Dispatchers.IO) {
            require()
            val minutes = maxAgeMinutes.coerceAtLeast(1)
            val cutoff = System.currentTimeMillis() - minutes * 60_000L
            // 取 50 条：够覆盖"刚发的验证码"，又不至于把整年的短信读进内存。
            val messages = query(
                selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?",
                args = arrayOf(
                    cutoff.toString(),
                    Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                ),
                limit = 50,
            )
            // 识别规则在 host-core 的 SmsCodeExtractor 里：纯函数，JVM 单测覆盖
            // "11 位手机号不算"、"关键词优先"这些真机上极难验证的分支。
            val code = SmsCodeExtractor.pick(messages.map { it.body }) ?: return@withContext null
            val source = messages.firstOrNull { SmsCodeExtractor.extract(it.body) == code }
                ?: messages.first()
            SmsCode(code = code, address = source.address, receivedAt = source.receivedAt)
        }

    private fun query(selection: String?, args: Array<String>?, limit: Int): List<SmsMessage> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        return resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            buildList {
                val capped = limit.coerceIn(1, 200)
                while (cursor.moveToNext() && size < capped) {
                    add(
                        SmsMessage(
                            id = cursor.getLong(0),
                            address = cursor.getString(1).orEmpty(),
                            body = cursor.getString(2).orEmpty(),
                            receivedAt = cursor.getLong(3),
                            inbound = cursor.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private companion object {
        // 识别规则已抽到 host-core/SmsCodeExtractor：纯函数，可在 JVM 上验证。
    }
}
