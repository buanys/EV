package ev.buan

import org.cordis.api.CalendarService
import org.cordis.api.CredentialService
import org.cordis.api.DeviceControlService
import org.cordis.api.HostPermission
import org.cordis.api.PluginStorage
import org.cordis.api.UserDataStorage

/**
 * 一次性把"按声明提供"的宿主服务都挂上。
 *
 * 五个服务的门禁规则完全一样（没声明 → 不给绑定；声明了 → 每次调用再复核授权），
 * 所以它们只该有一处装配。以前这五次调用散在 `HostActivation.activate` 中间，
 * 每加一个服务就把那个已经贴着 400 行上限的函数再往上顶。
 */
internal suspend fun provideDeclaredServices(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    storage: () -> PluginStorage,
    credentials: () -> CredentialService,
    calendar: () -> CalendarService,
    control: () -> DeviceControlService,
    userData: () -> UserDataStorage,
) {
    providePluginStorageIfDeclared(pluginContext, requested, storage)
    provideCredentialIfDeclared(pluginContext, requested, credentials)
    provideCalendarIfDeclared(pluginContext, requested, calendar)
    provideDeviceControlIfDeclared(pluginContext, requested, control)
    provideUserDataIfDeclared(pluginContext, requested, userData)
}
