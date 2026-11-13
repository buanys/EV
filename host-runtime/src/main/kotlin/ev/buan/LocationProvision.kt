package ev.buan

import org.cordis.api.HostPermission
import org.cordis.api.LocationService

/**
 * 按声明提供定位，与其它 `provide*IfDeclared` 同一套规则。
 *
 * 定位比其它只读能力更"贵"：主动定位会唤醒无线电、耗电，而且可能让整轮对话卡住。
 * 所以这道门除了常规的"没声明就不给绑定"，还多一层意义：未授权的插件连
 * `LocationService` 都拿不到，也就没有任何途径去触发一次 GPS 请求。
 */
internal suspend fun provideLocationIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    location: () -> LocationService,
): Boolean {
    if (HostPermission.LOCATION !in requested) return false
    pluginContext.provide(LocationService.Key, location()).awaitReady()
    return true
}
