package ev.buan.plugins.agent.browse

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import okhttp3.OkHttpClient
import org.cordis.api.PageReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网页工具：搜索与抓取。
 *
 * 两条工具的分工很清楚：
 * - `web_search` 只负责**找到链接**，返回标题 / 真实地址 / 摘要；
 * - `web_fetch` 负责**读那一页**，先静态 GET，抓回来是空壳才转离屏浏览器。
 *
 * 这个"先便宜后贵"的顺序不是优化小技巧，是必须的：离屏渲染要起 WebView、跑 JS、等加载，
 * 一次十几秒；而 90% 的页面用一次 GET 就够。
 */
internal class BrowseTools(
    private val client: OkHttpClient,
    /** 宿主没发布时为空：那就只剩静态抓取，JS 页面会如实报告抓不到，而不是假装成功。 */
    private val pageReader: PageReader?,
) {

    fun all(): List<Tool> = listOf(Tool_(SEARCH_SPEC) { search(it) }, Tool_(FETCH_SPEC) { fetch(it) })

    private suspend fun search(args: JSONObject): String {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return error("query 不能为空")
        val limit = args.optInt("limit", 8).coerceIn(1, 20)

        // 快路径：能直连 DDG 的网络里，一次 GET 就够。
        // 用短超时的独立客户端：国内这条线必然超时，不能让它把一次搜索拖到十几秒。
        if (!staticDead) {
            val hits = try {
                WebSearch.searchStatic(shortClient, query, limit)
            } catch (t: Throwable) {
                // 记下来，这个进程里不再试——省得每次搜索都白等一轮。
                staticDead = true
                emptyList()
            }
            if (hits.isNotEmpty()) {
                return JSONObject()
                    .put("query", query)
                    .put("via", "http")
                    .put("count", hits.size)
                    .put(
                        "results",
                        JSONArray().apply {
                            hits.forEach { h ->
                                put(
                                    JSONObject()
                                        .put("title", h.title)
                                        .put("url", h.url)
                                        .put("snippet", h.snippet),
                                )
                            }
                        },
                    )
                    .toString()
            }
        }

        // 慢路径：读屏。把引擎页渲染完，取结果区的可见文本交给模型。
        val reader = pageReader ?: return error(
            "这条网络上静态搜索不可用（结果页靠 JS 渲染），且宿主没有提供离屏浏览器，无法读屏搜索",
        )
        var lastError = "所有引擎都没有返回内容"
        for (engine in WebSearch.engines(query)) {
            val page = try {
                reader.render(engine.url, selector = engine.selector)
            } catch (t: Throwable) {
                lastError = "${engine.name} 渲染失败：${t.message ?: t.javaClass.simpleName}"
                continue
            }
            // 空文本比错误更糟：模型会据此回答"没搜到"，而实际只是被验证码拦了。换下一个引擎。
            if (!WebSearch.acceptable(page.text)) {
                lastError = page.error ?: "${engine.name} 的结果区没有可见文本"
                continue
            }
            return JSONObject()
                .put("query", query)
                .put("via", "browser")
                .put("engine", engine.name)
                .put("url", engine.url)
                .put("chars", page.text.length)
                .put("text", page.text)
                .put("hint", "以上是 ${engine.name} 结果页上被渲染出来的可见文本。请自行挑出相关条目，" +
                    "要读某一篇再用 web_fetch 打开它的地址。")
                .toString()
        }
        return error("读屏搜索失败：$lastError")
    }

    /** 静态搜索路径已确认不可用：同一进程内不再重试。 */
    @Volatile
    private var staticDead = false

    /** 只用于静态快路径的短超时客户端。 */
    private val shortClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()


    private suspend fun fetch(args: JSONObject): String {
        val url = args.optString("url", "").trim()
        if (url.isEmpty()) return error("url 不能为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return error("只支持 http/https，收到：$url")
        }
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(500, 60_000)

        val static = try {
            PageFetch.fetch(client, url, maxChars)
        } catch (t: Throwable) {
            // 不用 runCatching{}.getOrElse{return}：lambda 里的 return 会让整条表达式的
            // 类型推断退化成 Nothing，后面所有字段访问都变成 "Unresolved reference"。
            return error("抓取失败：${t.message ?: t.javaClass.simpleName}")
        }

        // 静态抓到了正文：直接用，不起浏览器。
        if (!static.looksEmpty) {
            return payload(url, static.title, static.text, "http", static.truncated, null)
        }

        // 空壳（SPA / 靠 JS 渲染）：只有这一种情况才付渲染的代价。
        val reader = pageReader
            ?: return payload(
                url, static.title, static.text, "http", static.truncated,
                "这一页几乎全是脚本，静态抓取拿不到正文；宿主没有提供离屏浏览器，无法渲染",
            )

        val rendered = reader.render(url)
        if (rendered.text.isBlank()) {
            return payload(
                url, static.title, static.text, "http", static.truncated,
                rendered.error ?: "离屏浏览器渲染完成但页面没有可见文本",
            )
        }
        val text = if (rendered.text.length > maxChars) rendered.text.take(maxChars) else rendered.text
        return payload(
            url,
            rendered.title.ifEmpty { static.title },
            text,
            "browser",
            rendered.truncated || text.length < rendered.text.length,
            null,
        )
    }

    private fun payload(
        url: String,
        title: String,
        text: String,
        via: String,
        truncated: Boolean,
        warning: String?,
    ): String = JSONObject().apply {
        put("url", url)
        put("title", title)
        // via 很重要：模型据此知道自己拿到的是"页面源码里的字"还是"JS 跑完之后的字"，
        // 判断内容完整性时这两个不是一回事。
        put("via", via)
        put("chars", text.length)
        put("truncated", truncated)
        put("text", text)
        if (warning != null) put("warning", warning)
    }.toString()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private class Tool_(
        override val spec: ToolSpec,
        private val operation: suspend (JSONObject) -> String,
    ) : Tool {
        override suspend fun invoke(argumentsJson: String): String = runCatching {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            operation(args)
        }.getOrElse { t ->
            error("${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}")
        }
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 12_000

        val SEARCH_SPEC = ToolSpec(
            name = "web_search",
            description = "联网搜索网页。返回标题、真实链接和摘要；要读全文再用 web_fetch 打开链接。",
            parametersJson = """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"搜索关键词"},
                  "limit":{"type":"integer","description":"返回条数，默认 8，上限 20"}
                },"required":["query"],"additionalProperties":false}
            """.trimIndent(),
        )

        val FETCH_SPEC = ToolSpec(
            name = "web_fetch",
            description = "打开一个网页并返回正文文本。自动处理需要 JS 渲染的页面。",
            parametersJson = """
                {"type":"object","properties":{
                  "url":{"type":"string","description":"要读取的 http/https 地址"},
                  "max_chars":{"type":"integer","description":"正文上限，默认 12000，最大 60000"}
                },"required":["url"],"additionalProperties":false}
            """.trimIndent(),
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
