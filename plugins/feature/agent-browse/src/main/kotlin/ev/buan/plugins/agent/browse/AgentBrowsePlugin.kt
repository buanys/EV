package ev.buan.plugins.agent.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.ToolRegistry
import okhttp3.OkHttpClient
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentPage
import org.cordis.api.AgentSurface
import org.cordis.api.PageReader
import org.cordis.api.RuntimeBudgetService
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 联网能力：`web_search` + `web_fetch`。
 *
 * 两条依赖都是**可选**的：
 * - 工具总线（`agent-tools`）没起来 → 什么都不注册，安静退出；
 * - 离屏浏览器（宿主 `PageReader`）没起来 → 照样注册，只是 JS 页面会如实报告抓不到。
 *
 * 后者是关键：宁可让模型看到"这一页需要渲染但渲染不可用"，也不要让它以为网页就是空的。
 *
 * 浏览器面板也归这里：它在屏幕上是给人用的 WebView，但背后的网络能力、权限与失败处理跟
 * `web_fetch` 是同一套；放在 ev-ui 里会让聊天界面依赖 WebView 的生命周期。
 */
class AgentBrowsePlugin :
    Plugin<Unit>,
    AgentSurface,
    AgentPage,
    org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.browse",
        name = "浏览器",
        version = "1.1.0",
        description = "网页抓取与搜索，供模型查资料；也提供界面里的浏览器面板。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("web_fetch", org.cordis.api.PluginCapability.Kind.TOOL, "抓取网页", "取正文文本，供模型阅读。"),
            org.cordis.api.PluginCapability("web_search", org.cordis.api.PluginCapability.Kind.TOOL, "搜索", "按关键词搜索并返回结果列表。"),
        ),
        permissions = listOf("android.permission.INTERNET"),
    )

    override val name = "agent-browse"
    override val title = "联网搜索"
    override val body = "web_search 搜索网页、web_fetch 读页面正文；需要 JS 的页面自动交给离屏浏览器。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, RuntimeBudgetService.Key)

    override val pageTitle: String = "浏览器"

    /** Opened from a menu, never the app root. */
    override val isHomeCandidate: Boolean = false

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) {
        BrowserPage(onBack = onBack, modifier = modifier)
    }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key)
            ?: error("工具总线未就绪：agent-tools 必须先激活（sidecar 依赖 ev.buan.plugins.agent.tools）")
        // 每次活跃期建一个客户端：连接池随插件生命周期走，禁用插件就释放。
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        registry.register(
            PLUGIN_ID,
            BrowseTools(
                client = client,
                pageReader = directory.lookup(PageReader.Key),
                // 宿主的执行预算：渲染走 Long 档位，由宿主决定这一刻能不能开。
                budget = ctx.require(RuntimeBudgetService.Key),
            ).all(),
        )
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.browse"
    }
}
