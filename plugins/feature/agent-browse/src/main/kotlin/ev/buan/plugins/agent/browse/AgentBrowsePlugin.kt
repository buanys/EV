package ev.buan.plugins.agent.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 联网能力：`web_search` + `web_fetch`，加上内置浏览器的那一组动作与面板。
 *
 * 两条依赖都是**可选**的：
 * - 工具总线（`agent-tools`）没起来 → 什么都不注册，安静退出；
 * - 离屏浏览器（宿主 `PageReader`）没起来 → 照样注册，只是 JS 页面会如实报告抓不到。
 *
 * 后者是关键：宁可让模型看到"这一页需要渲染但渲染不可用"，也不要让它以为网页就是空的。
 *
 * 浏览器面板也归这里：它在屏幕上是给人看的一页，但背后的网络能力、权限与失败处理跟
 * `web_fetch` 是同一套；放在 ev-ui 里会让聊天界面依赖 WebView 的生命周期。
 *
 * ## 面板与引擎的分工（这次重构的重点）
 *
 * WebView 归**宿主**（[org.cordis.api.BrowserService]）：窗口、页面、历史、cookie 都在那边，
 * agent 的 `browser_*` 工具与用户眼前这一页是同一个页面。插件这边只有 [BrowserPanel]——
 * 一个遥控器加一面镜子：它把点击翻成宿主的调用，把宿主的快照画成标签条与地址栏。
 *
 * 于是"退出浏览器页再回来，页面还在"不再是这个类要操心的事：[BrowserPanel.release]
 * 只放下视图，宿主里的窗口一个不少。
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
        version = "2.0.0",
        description = "网页抓取与搜索，供模型查资料；内置浏览器支持多窗口，界面与 agent 共用同一组页面。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("web_fetch", org.cordis.api.PluginCapability.Kind.TOOL, "抓取网页", "取正文文本，供模型阅读。"),
            org.cordis.api.PluginCapability("web_search", org.cordis.api.PluginCapability.Kind.TOOL, "搜索", "按关键词搜索并返回结果列表。"),
        ),
        permissions = listOf("android.permission.INTERNET"),
    )

    override val name = "agent-browse"
    override val title = "联网搜索"
    override val body = "web_search 搜索网页、web_fetch 读页面正文；需要 JS 的页面交给内置浏览器。" +
        "浏览器支持多窗口，agent 与界面看到的是同一组页面。"

    override val inject: Dependencies = dependencies(
        ServiceDirectory.Key,
        RuntimeBudgetService.Key,
        // 浏览器会话：没声明 BROWSER 时宿主根本不会提供绑定，插件会停在 PENDING——
        // 这正是"要么能浏览、要么别起"的表达。
        org.cordis.api.BrowserService.Key,
    )

    override val pageTitle: String = "浏览器"

    /** Opened from a menu, never the app root. */
    override val isHomeCandidate: Boolean = false

    /**
     * 界面的那一层：遥控器 + 镜子。挂在插件实例上而不是组合里，是为了让地址栏、最近站点
     * 这些**界面记忆**不因为一次进出而丢掉（页面本身在宿主里，本来就不会丢）。
     */
    private var panel: BrowserPanel? = null

    /** 界面动作要发到宿主的 suspend 接口上；插件停用时一起收掉。 */
    private var scope: CoroutineScope? = null

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) {
        val current = panel ?: return
        BrowserPage(onBack = onBack, modifier = modifier, panel = current)
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
        val service = ctx.require(org.cordis.api.BrowserService.Key)
        val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ownPanel = BrowserPanel(service, panelScope)
        panel = ownPanel
        scope = panelScope
        registry.register(
            PLUGIN_ID,
            BrowseTools(
                client = client,
                pageReader = directory.lookup(PageReader.Key),
                // 宿主的执行预算：渲染走 Long 档位，由宿主决定这一刻能不能开。
                budget = ctx.require(RuntimeBudgetService.Key),
            ).all() + BrowserTools.all(service),
        )
        effect.collect {
            // 插件停用：只放下界面。页面归宿主，销毁它不归我们决定——
            // 真正的销毁在窗口关掉、内存压力与宿主退出这三处。
            ownPanel.release()
            panel = null
            panelScope.cancel()
            scope = null
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.browse"
    }
}
