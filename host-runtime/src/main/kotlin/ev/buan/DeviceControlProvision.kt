package ev.buan

import org.cordis.api.DeviceControlService
import org.cordis.api.HostPermission

/**
 * 按声明提供设备控制，与 `provideCalendarIfDeclared` / `provideCredentialIfDeclared` 同一套规则。
 *
 * 它比日历更需要这道门：一个能改音量、断网、开勿扰的接口，如果发给每个插件，
 * 用户就没有任何地方能说"这个插件不许动我的设备"。
 */
internal suspend fun provideDeviceControlIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    control: () -> DeviceControlService,
): Boolean {
    if (HostPermission.DEVICE_CONTROL !in requested) return false
    pluginContext.provide(DeviceControlService.Key, control()).awaitReady()
    return true
}
