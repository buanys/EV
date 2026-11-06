package ev.buan.plugins.ev.ui

import org.cordis.api.PluginManifest

/**
 * 这个容器给宿主看的那份自述。
 *
 * 放在类外是因为它是**数据**，不是行为：插件被停用、重载、重新扫的时候，
 * 宿主读到的永远是同一份声明，和 `EvUiPlugin` 实例的存活无关。
 */

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    internal val evUiManifest: PluginManifest = PluginManifest(
        id = "ev.buan.plugins.ev.ui",
        name = "EV 界面",
        version = "0.1.0",
        description = "主界面：对话、抽屉、快捷菜单、终端、设置页。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("agent-surface", org.cordis.api.PluginCapability.Kind.UI, "主界面", "宿主首页渲染的聊天界面。"),
            org.cordis.api.PluginCapability("agent-page", org.cordis.api.PluginCapability.Kind.UI, "独立页面", "宿主可为插件单独打开的页面。"),
        ),
    )
