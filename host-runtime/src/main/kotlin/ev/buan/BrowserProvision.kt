package ev.buan

import org.cordis.api.BrowserService
import org.cordis.api.HostPermission

/**
 * 按声明提供浏览器会话，与其它 `provide*IfDeclared` 同一套规则。
 *
 * 会话是**全应用共享的一个**（见 HostComposition）：浏览器带着用户的 cookie，
 * 每个插件各起一个既浪费内存，也会让"同一个站点两个登录态"变成排查噩梦。
 */
internal suspend fun provideBrowserIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    browser: () -> BrowserService,
): Boolean {
    if (HostPermission.BROWSER !in requested) return false
    pluginContext.provide(BrowserService.Key, browser()).awaitReady()
    return true
}
