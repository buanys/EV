package ev.buan.plugins.ev.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cordis.api.PluginPageService
import org.cordis.api.ServiceDirectory
import ev.buan.ui.EvThemeTokens
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/**
 * 另一颗插件自己的页面，在 ev-ui 的导航栈里被组合进来。
 *
 * 这样全屏页面的转场、返回手势和页面栈仍然归 ev-ui；被组合的插件可能在这页开着的时候
 * 被停用或重载，所以每次组合都重新解析一次页面实例。
 */

/**
 * Composes another plugin's page inside ev-ui's own navigation stack.
 *
 * [PluginPageService] hands back the plugin's [AgentPage] instead of starting an Activity, which is
 * what keeps the transition and the back gesture identical to a built-in page. The page is resolved
 * on every composition on purpose: the plugin may be disabled, reloaded or fail to activate while
 * this screen is open, and a stale instance must not keep rendering.
 */
@Composable
internal fun EvPluginPage(
    pluginId: String,
    directory: ServiceDirectory?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val page = directory?.lookup(PluginPageService.Key)?.pageOf(pluginId)
    if (page != null) {
        page.Page(modifier, onBack)
        return
    }
    // Fully qualified: `EvPage` in this package is the navigation sealed interface, not the
    // design-system page composable.
    ev.buan.ui.EvPage(title = "插件未就绪", onBack = onBack, modifier = modifier) {
        item {
            Text(
                text = "这个页面属于 $pluginId，它现在没有加载。到插件中心看看它是否被禁用或加载失败。",
                style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textSecondary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
