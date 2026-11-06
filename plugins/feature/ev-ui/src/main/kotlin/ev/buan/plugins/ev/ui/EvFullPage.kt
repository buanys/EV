package ev.buan.plugins.ev.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.ev.ui.ui.EvLinuxScreen
import ev.buan.plugins.ev.ui.ui.EvModelProvidersScreen
import ev.buan.plugins.ev.ui.ui.EvPluginCatalogScreen
import ev.buan.plugins.ev.ui.ui.EvSettingsScreen
import ev.buan.plugins.ev.ui.ui.EvThemePickerScreen
import ev.buan.ui.EvThemeSpec
import org.cordis.api.AppearanceService
import org.cordis.api.HudService
import org.cordis.api.PluginCatalogService
import org.cordis.api.PluginManagerService
import org.cordis.api.ServiceDirectory

/**
 * 全屏页面的分发：一个页面一个分支，别的什么都不做。
 *
 * 从 `EvUiPlugin.ChatSurface` 里拆出来，因为这一段**只读页面栈和几个服务**，
 * 不碰任何对话状态；而它原来占掉了那块 350 行的三分之一，让"聊天界面到底怎么组装"
 * 淹没在一堆 `onBack = { ... }` 里。
 *
 * 加一个页面仍然是两处改动：这里加一个分支，调用处加一次 push。返回键不用动——
 * 页面栈是唯一的真相源（见 `EvPageStack`）。
 */
@Composable
internal fun EvFullPage(
    page: EvPage,
    modifier: Modifier,
    appearance: AppearanceService?,
    directory: ServiceDirectory?,
    hud: HudService?,
    hudPreview: Boolean,
    onToggleHudPreview: (Boolean) -> Unit,
    availableThemes: List<EvThemeSpec>,
    onPop: () -> Unit,
    onPush: (EvPage) -> Unit,
    onOpenSection: (SettingsChild) -> Unit,
    onOpenPluginManager: () -> Unit,
    onOpenHostSettings: () -> Unit,
) {
    when (page) {
        EvPage.Settings -> {
            // Without the host appearance service the settings page cannot render, so the
            // conversation stays visible rather than showing an empty surface.
            if (appearance != null) {
                EvSettingsScreen(
                    appearance = appearance,
                    onBack = { onPop() },
                    onOpenPlugins = { onOpenPluginManager() },
                    onOpenPluginCatalog = { onPush(EvPage.Plugins) },
                    onOpenLinux = { onPush(EvPage.Linux) },
                    onOpenHostSettings = { onOpenHostSettings() },
                    onOpenThemes = { onPush(EvPage.SettingsSection(SettingsChild.Themes)) },
                    themeName = availableThemes.firstOrNull { it.id == appearance.current.themeId }?.name
                        ?: availableThemes.first().name,
                    onOpenModels = { onPush(EvPage.SettingsSection(SettingsChild.Models)) },
                    onOpenTools = { onPush(EvPage.SettingsSection(SettingsChild.Tools)) },
                    onOpenMemory = { onPush(EvPage.SettingsSection(SettingsChild.Memory)) },
                    // 宿主没发布提示层时是 null，设置页那一行只会显示"不可用"，不会崩。
                    hud = hud,
                    hudPreview = hudPreview,
                    onToggleHudPreview = onToggleHudPreview,
                    modifier = modifier,
                )
            }
        }

        is EvPage.SettingsSection -> {
            // A second-level settings page keeps the root underneath: back returns to it.
            when (page.section) {
                SettingsChild.Themes -> EvThemePickerScreen(
                    themes = availableThemes,
                    currentId = appearance?.current?.themeId.orEmpty(),
                    // The host owns the appearance state: selecting a theme persists it and
                    // recomposes the whole app, this page only asks for the change.
                    onSelect = { id -> appearance?.update { it.copy(themeId = id) } },
                    onBack = { onPop() },
                    modifier = modifier,
                )

                SettingsChild.Models -> EvModelProvidersScreen(
                    service = { directory?.lookup(ModelProviderService.Key) },
                    onBack = { onPop() },
                    modifier = modifier,
                )

                SettingsChild.Tools -> EvPluginPage(
                    pluginId = TOOLS_PLUGIN_ID,
                    directory = directory,
                    onBack = { onPop() },
                    modifier = modifier,
                )

                SettingsChild.Memory -> EvPluginPage(
                    pluginId = MEMORY_PLUGIN_ID,
                    directory = directory,
                    onBack = { onPop() },
                    modifier = modifier,
                )

            }
        }

        EvPage.Tools -> {
            // The registry's owner draws the panel now; the drawer still routes here.
            EvPluginPage(
                pluginId = TOOLS_PLUGIN_ID,
                directory = directory,
                onBack = { onPop() },
                modifier = modifier,
            )
        }

        EvPage.Browser -> {
            // The browse plugin owns the panel now: same network stack as web_fetch.
            EvPluginPage(
                pluginId = BROWSE_PLUGIN_ID,
                directory = directory,
                onBack = { onPop() },
                modifier = modifier,
            )
        }
        EvPage.Terminal -> {
            // The shell's owner draws the console; the quick tools panel still routes here.
            EvPluginPage(
                pluginId = SHELL_PLUGIN_ID,
                directory = directory,
                onBack = { onPop() },
                modifier = modifier,
            )
        }

        is EvPage.Plugin -> {
            // A page that belongs to another plugin, composed here so the transition, the back
            // gesture and the page stack stay ev-ui's. The plugin may be disabled or reloaded while
            // this is on screen, so the page is resolved on every composition.
            EvPluginPage(
                pluginId = page.id,
                directory = directory,
                onBack = { onPop() },
                modifier = modifier,
            )
        }

        EvPage.Memory -> {
            // Kept as an alias: the drawer still navigates here, and both routes now open the
            // memory plugin's own page instead of ev-ui's copy of it.
            EvPluginPage(
                pluginId = MEMORY_PLUGIN_ID,
                directory = directory,
                onBack = { onPop() },
                modifier = modifier,
            )
        }

        EvPage.Linux -> {
            // A view over the linux plugin: settings through the catalog, actions through the tool bus.
            EvLinuxScreen(
                registry = { directory?.lookup(ToolRegistry.Key) },
                catalog = directory?.lookup(org.cordis.api.PluginCatalogService.Key),
                onBack = { onPop() },
                modifier = modifier,
            )
        }

        EvPage.Plugins -> {
            // The catalog is host-owned: plugins declare, the host collects, this page renders.
            EvPluginCatalogScreen(
                catalog = directory?.lookup(org.cordis.api.PluginCatalogService.Key),
                manager = directory?.lookup(org.cordis.api.PluginManagerService.Key),
                onBack = { onPop() },
                modifier = modifier,
            )
        }


    }
}
