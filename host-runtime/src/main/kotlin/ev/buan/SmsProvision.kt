package ev.buan

import org.cordis.api.HostPermission
import org.cordis.api.SmsService

/**
 * 按声明提供短信读取，与其它 `provide*IfDeclared` 同一套规则。
 *
 * 短信是全设备最敏感的一类数据：一条验证码就等于一个账号。所以它单独一位权限，
 * 不并进通讯录——用户可以允许"知道谁给我打过电话"，同时不允许"看我的短信"。
 */
internal suspend fun provideSmsIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    sms: () -> SmsService,
): Boolean {
    if (HostPermission.SMS !in requested) return false
    pluginContext.provide(SmsService.Key, sms()).awaitReady()
    return true
}
