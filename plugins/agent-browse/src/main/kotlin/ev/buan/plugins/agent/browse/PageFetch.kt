package ev.buan.plugins.agent.browse

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 静态抓取：一次 GET 拿到 HTML，抽出正文。
 *
 * 这条路覆盖绝大多数页面（文档、博客、新闻、维基），成本是一次 HTTP 请求。
 * **只有当它抓回来是空壳时**，调用方才该转到离屏浏览器——一次 WebView 渲染要起进程内
 * 浏览器、跑 JS、等加载，比 GET 贵两个数量级。所以这里要能明确回答"我抓到的够不够用"。
 */
internal object PageFetch {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * 低于这个字符数就认为"没抓到正文"。
     *
     * 200 是个经验值：正常的文章页至少几百字；而 SPA 的初始 HTML 往往只有
     * `<div id="app"></div>` 加一堆 script，抽出来不到一百个字符。
     */
    const val MIN_USEFUL_CHARS = 200

    data class Fetched(
        val url: String,
        val title: String,
        val text: String,
        /** 内容是纯文本（text 类 MIME、JSON 等），没有 HTML 可抽。 */
        val plain: Boolean = false,
        val truncated: Boolean = false,
        /** 页面里有没有 script 标签。结合正文长度用来判断"是不是靠 JS 渲染的"。 */
        val hasScripts: Boolean = false,
    ) {
        /**
         * 抓到了但几乎没内容，**而且**页面带脚本 —— 典型的需要 JS 渲染。
         *
         * 只按字数判断是错的：一篇真的只有两句话的公告页也会低于阈值，
         * 那种页面送去渲染既浪费十几秒，又把一句正确的话换成一次超时。
         * 带脚本 + 正文极短，才是"初始 HTML 只有挂载点"的特征。
         */
        val looksEmpty: Boolean get() = !plain && text.length < MIN_USEFUL_CHARS && hasScripts
    }

    fun fetch(client: OkHttpClient, url: String, maxChars: Int): Fetched {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val raw = response.body.string()

            // 非 HTML 直接原样给模型：JSON 接口、纯文本、Markdown 都不该被当标签剥掉。
            if (contentType.isNotEmpty() && !contentType.contains("html")) {
                val (text, cut) = clamp(raw, maxChars)
                return Fetched(url, title = "", text = text, plain = true, truncated = cut)
            }

            return extractFromHtml(raw, url, maxChars)
        }
    }

    /**
     * HTML → 正文。抽成纯函数是为了能单测：抓取挂掉时最需要区分
     * "网络不通" 和 "解析规则变了"，后者只能靠喂真实 HTML 才能测出来。
     */
    fun extractFromHtml(html: String, url: String, maxChars: Int): Fetched {
            val doc = Jsoup.parse(html, url)
            val title = doc.title().trim()
            // 在移除 script 之前先记下有没有：移除之后就没法区分"内容真的短"和"内容还没生成"。
            val hasScripts = !doc.select("script").isEmpty()

            // 脚本、样式、导航这些不是正文；留着只会让模型在一堆菜单项里找内容。
            doc.select("script, style, noscript, svg, nav, header, footer, aside, form, iframe")
                .forEach { it.remove() }

            // 优先语义容器：有 <article>/<main> 的页面，正文几乎都在里面，
            // 直接取它比取整个 body 少一大堆推荐位和评论。
            val container = doc.selectFirst("article")
                ?: doc.selectFirst("main")
                ?: doc.selectFirst("[role=main]")
                ?: doc.body()

            val text = (container?.wholeText() ?: "")
                .replace(Regex("[ \\t\\u00a0]+"), " ")
                .replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
                .trim()

            val (clamped, cut) = clamp(text, maxChars)
            return Fetched(url, title, clamped, plain = false, truncated = cut, hasScripts = hasScripts)
    }

    private fun clamp(text: String, maxChars: Int): Pair<String, Boolean> =
        if (text.length <= maxChars) text to false else text.take(maxChars) to true
}
