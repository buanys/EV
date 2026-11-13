package ev.buan

import org.cordis.api.ContactsService
import org.cordis.api.HostPermission

/**
 * 按声明提供通讯录，与 `provideCalendarIfDeclared` / `provideDeviceControlIfDeclared` 同一套规则。
 *
 * 为什么这道门必须在装配阶段：`ContactsService` 背后是宿主的 `READ_CONTACTS` 运行时权限，
 * 而运行时权限是整包的。绑定一旦发出去，插件能读多少人由宿主说了算——
 * 所以"发不发"这件事不能留给运行期。
 */
internal suspend fun provideContactsIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    contacts: () -> ContactsService,
): Boolean {
    if (HostPermission.CONTACTS !in requested) return false
    pluginContext.provide(ContactsService.Key, contacts()).awaitReady()
    return true
}
