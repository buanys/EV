package ev.buan

import org.cordis.api.BrowserService
import org.cordis.api.CalendarService
import org.cordis.api.ContactsService
import org.cordis.api.CredentialService
import org.cordis.api.DeviceControlService
import org.cordis.api.HostPermission
import org.cordis.api.LocationService
import org.cordis.api.MediaService
import org.cordis.api.PluginStorage
import org.cordis.api.SmsService
import org.cordis.api.UserDataStorage

/**
 * 一次性把"按声明提供"的宿主服务都挂上。
 *
 * 这些服务的门禁规则完全一样（没声明 → 不给绑定；声明了 → 每次调用再复核授权），
 * 所以它们只该有一处装配。以前这几次调用散在 `HostActivation.activate` 中间，
 * 每加一个服务就把那个已经贴着 400 行上限的函数再往上顶。
 *
 * 注意参数是**工厂**而不是实例：未声明的服务连构造都不该发生，
 * 否则一个没授权的插件也会让宿主去建一个它永远拿不到的对象。
 */
internal suspend fun provideDeclaredServices(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    storage: () -> PluginStorage,
    credentials: () -> CredentialService,
    calendar: () -> CalendarService,
    control: () -> DeviceControlService,
    browser: () -> BrowserService,
    userData: () -> UserDataStorage,
    contacts: () -> ContactsService,
    sms: () -> SmsService,
    media: () -> MediaService,
    location: () -> LocationService,
) {
    providePluginStorageIfDeclared(pluginContext, requested, storage)
    provideCredentialIfDeclared(pluginContext, requested, credentials)
    provideCalendarIfDeclared(pluginContext, requested, calendar)
    provideDeviceControlIfDeclared(pluginContext, requested, control)
    provideBrowserIfDeclared(pluginContext, requested, browser)
    provideUserDataIfDeclared(pluginContext, requested, userData)
    provideContactsIfDeclared(pluginContext, requested, contacts)
    provideSmsIfDeclared(pluginContext, requested, sms)
    provideMediaIfDeclared(pluginContext, requested, media)
    provideLocationIfDeclared(pluginContext, requested, location)
}
