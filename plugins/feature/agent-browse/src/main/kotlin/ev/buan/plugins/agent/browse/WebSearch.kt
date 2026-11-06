package ev.buan.plugins.agent.browse

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网页搜索。
 *
 * 两条路径，顺序是"先便宜、失败就转读屏"：
 *
 * 1. **静态**：抓结果页的 HTML 解析。在能直连 DDG 的网络里，一次 GET 就完成。
 * 2. **读屏**：用离屏浏览器把引擎页跑完 JS，取结果区的可见文本交给模型。
 *
 * 为什么必须有第 2 条：国内网络下静态这条路**整体不可用**——
 * DuckDuckGo 连不上，百度/Bing 的结果靠 JS 渲染（静态 HTML 只有骨架），
 * 搜狗直接回反爬验证页。这不是"某个选择器要更新"，是整个抓法在这里不成立。
 * 读屏不做结构解析：引擎改版只影响美观，不影响"模型能不能看到结果"。
 */
internal object WebSearch {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    data class Hit(val title: String, val url: String, val snippet: String)

    /**
     * 引擎与它的结果容器。
     *
     * [selector] 只用来**缩小取文本的范围**，不用于解析结构——所以引擎改版时，
     * 最坏情况是选择器匹配不到（退回整页），而不是完全搜不到东西。
     */
    data class Engine(val name: String, val url: String, val selector: String)

    /**
     * 读屏引擎，按顺序尝试。
     *
     * Bing 排第一：它对无 cookie 的 WebView 最宽松，结果区也干净。
     * 百度必然能打开，但广告多，作为第二选择。
     *
     * 返回纯数据而不是在这里发请求：渲染是 suspend 且依赖宿主契约，
     * 留在这里会让这段"顺序与选择器"的决策没法单测。
     */
    fun engines(query: String): List<Engine> = listOf(
        Engine("bing", "https://cn.bing.com/search?q=" + enc(query), "#b_results"),
        Engine("baidu", "https://www.baidu.com/s?wd=" + enc(query), "#content_left"),
    )

    /**
     * 读回来的文本够不够当结果用。
     *
     * 少于这个字数基本等于没读到：要么被验证码拦了，要么选择器失效。
     * 把它当成功的话，模型会得到一段空白并据此回答"没搜到"——比报错更糟。
     */
    fun acceptable(text: String): Boolean = text.length >= MIN_READABLE

    // ---- 路径 1：静态抓取 ----

    fun searchStatic(client: OkHttpClient, query: String, limit: Int): List<Hit> {
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=" + enc(query))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return parse(body, limit)
        }
    }

    fun parse(html: String, limit: Int): List<Hit> {
        val doc = Jsoup.parse(html)
        return doc.select("div.result")
            .asSequence()
            .mapNotNull { el ->
                val anchor = el.selectFirst("a.result__a") ?: return@mapNotNull null
                val title = anchor.text().trim()
                if (title.isEmpty()) return@mapNotNull null
                val url = resolveHref(anchor.attr("href"))
                if (url.isEmpty()) return@mapNotNull null
                val snippet = (el.selectFirst("a.result__snippet")
                    ?: el.selectFirst(".result__snippet")
                    ?: el.selectFirst(".result-snippet"))
                    ?.text()?.trim().orEmpty()
                Hit(title, url, snippet)
            }
            .take(limit)
            .toList()
    }

    private fun resolveHref(href: String): String {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return ""
        val encoded = if (trimmed.contains("uddg=")) {
            trimmed.substringAfter("uddg=").substringBefore("&")
        } else {
            ""
        }
        if (encoded.isNotEmpty()) {
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
        }
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }

    // ---- 路径 2：读屏 ----

    /** 少于这个字数基本等于没读到结果：要么被验证码拦住，要么选择器失效了。 */
    private const val MIN_READABLE = 80

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
