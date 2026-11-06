package ev.buan

import org.cordis.api.CalendarService
import org.cordis.api.HostPermission

/**
 * 按声明提供日历服务，与 `provideCredentialIfDeclared` / `providePluginStorageIfDeclared`
 * 同一套规则：没写 `CALENDAR` 的插件根本拿不到绑定。
 *
 * 为什么这条门必须在这里：`CalendarService` 背后是宿主的 `READ_WRITE_CALENDAR` 运行时权限，
 * 而运行时权限是整包的。一旦绑定发出去，插件能读多少由宿主说了算——所以"发不发"这件事
 * 必须在装配阶段就按声明决定。
 */
internal suspend fun provideCalendarIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    calendar: () -> CalendarService,
): Boolean {
    if (HostPermission.CALENDAR !in requested) return false
    pluginContext.provide(CalendarService.Key, calendar()).awaitReady()
    return true
}
