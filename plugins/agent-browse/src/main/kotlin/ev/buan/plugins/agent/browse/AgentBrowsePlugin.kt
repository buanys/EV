package ev.buan.plugins.agent.browse

import ev.buan.plugins.agent.api.ToolRegistry
import okhttp3.OkHttpClient
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PageReader
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
 */
class AgentBrowsePlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-browse"
    override val title = "联网搜索"
    override val body = "web_search 搜索网页、web_fetch 读页面正文；需要 JS 的页面自动交给离屏浏览器。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key) ?: return
        // 每次活跃期建一个客户端：连接池随插件生命周期走，禁用插件就释放。
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        registry.register(PLUGIN_ID, BrowseTools(client, directory.lookup(PageReader.Key)).all())
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.browse"
    }
}
