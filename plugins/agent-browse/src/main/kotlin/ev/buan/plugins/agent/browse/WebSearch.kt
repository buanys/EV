package ev.buan.plugins.agent.browse

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网页搜索：抓 DuckDuckGo 的 HTML 版结果页。
 *
 * 为什么是 HTML 版而不是 API：`html.duckduckgo.com` 是给无 JS 客户端用的纯 HTML 页面，
 * 不需要 key、不需要浏览器、解析稳定。真正的 API 要申请 key，而这件事的价值主要在
 * "模型能不能查到东西"，先不引入一个要用户配置的依赖。
 *
 * 换搜索引擎时只需要改 [parse] 的 selector 与 [ENDPOINT]——这是刻意的：
 * 搜索质量不满意时，替换成本应该是一处而不是一层。
 */
internal object WebSearch {

    private const val ENDPOINT = "https://html.duckduckgo.com/html/"

    /**
     * 必须伪装成普通浏览器。默认的 `OkHttp` UA 会被直接返回 202 + 空结果页，
     * 那不是解析出错，是对方不想服务脚本客户端。
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    data class Hit(val title: String, val url: String, val snippet: String)

    fun search(client: OkHttpClient, query: String, limit: Int): List<Hit> {
        val request = Request.Builder()
            .url(ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8"))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("搜索请求失败：HTTP ${response.code}")
            }
            return parse(body, limit)
        }
    }

    /**
     * 解析结果页。抽成独立函数是为了能拿真实 HTML 片段做单测——
     * 搜索这种功能挂掉时，最需要的就是"到底是解析变了还是网络不通"。
     */
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
                // DDG 不同版本里摘要的 class 名换过几次，逐个试比只认一个稳。
                val snippet = (el.selectFirst("a.result__snippet")
                    ?: el.selectFirst(".result__snippet")
                    ?: el.selectFirst(".result-snippet"))
                    ?.text()?.trim().orEmpty()
                Hit(title, url, snippet)
            }
            .take(limit)
            .toList()
    }

    /**
     * DDG 的链接是跳转形式：`//duckduckgo.com/l/?uddg=<encoded>&rut=…`。
     * 不解码的话，模型拿到的是一堆 duckduckgo.com 的跳转地址，点开才落地——
     * 而它接下来要 `web_fetch` 的是真实地址。
     */
    private fun resolveHref(href: String): String {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return ""
        val encoded = when {
            trimmed.contains("uddg=") -> trimmed.substringAfter("uddg=").substringBefore("&")
            else -> ""
        }
        if (encoded.isNotEmpty()) {
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
        }
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }
}
