package ev.buan.plugins.agent.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索解析与正文提取。
 *
 * 这两块是"联网能力"里唯一可能悄悄坏掉的地方——网络不通会报错，
 * 但**解析规则变了只会返回空列表**，看起来像"没搜到"。所以拿真实结构的 HTML 片段钉住。
 */
class WebSearchTest {

    /** DuckDuckGo HTML 版的结构，链接是 uddg 跳转形式。 */
    private val ddgHtml = """
        <html><body>
        <div class="result results_links results_links_deep web-result">
          <div class="links_main links_deep result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a"
                 href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F&amp;rut=abc">Kotlin 文档</a>
            </h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Kotlin 官方文档与教程</a>
          </div>
        </div>
        <div class="result results_links web-result">
          <div class="links_main result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a"
                 href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2FJetBrains%2Fkotlin&amp;rut=def">GitHub - JetBrains/kotlin</a>
            </h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=y">The Kotlin programming language</a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `解析出标题 真实地址 与摘要`() {
        val hits = WebSearch.parse(ddgHtml, limit = 10)

        assertEquals(2, hits.size)
        assertEquals("Kotlin 文档", hits[0].title)
        assertEquals("Kotlin 官方文档与教程", hits[0].snippet)
    }

    @Test
    fun `跳转链接被还原成真实地址`() {
        val hits = WebSearch.parse(ddgHtml, limit = 10)

        // 不解码的话模型拿到的是 duckduckgo.com 的跳转地址，后续 web_fetch 会再绕一层。
        assertEquals("https://kotlinlang.org/docs/", hits[0].url)
        assertEquals("https://github.com/JetBrains/kotlin", hits[1].url)
    }

    @Test
    fun `limit 生效`() {
        assertEquals(1, WebSearch.parse(ddgHtml, limit = 1).size)
    }

    @Test
    fun `没有结果时返回空列表而不是崩溃`() {
        // 被限流时 DDG 回的就是这种"结构完整但没有 result 节点"的页面。
        assertTrue(WebSearch.parse("<html><body>no results</body></html>", 10).isEmpty())
    }

    @Test
    fun `缺链接的结果被跳过`() {
        val html = """
            <div class="result"><h2 class="result__title"><a class="result__a">无 href</a></h2></div>
        """.trimIndent()

        assertTrue("没有 href 的结果对 agent 没用", WebSearch.parse(html, 10).isEmpty())
    }
}

class PageFetchTest {

    @Test
    fun `正常文章抽取正文并去掉脚本与导航`() {
        val html = """
            <html><head><title>标题</title></head><body>
              <nav>首页 | 关于</nav>
              <article>
                <h1>正文标题</h1>
                <p>第一段内容。</p>
                <p>第二段内容。</p>
                <p>${"这是一段足够长的正文，用来把总字数推过空壳判定的阈值，".repeat(8)}</p>
              </article>
              <script>console.log('不该出现')</script>
              <footer>版权信息</footer>
            </body></html>
        """.trimIndent()

        val page = PageFetch.extractFromHtml(html, "https://example.com/a", 10_000)

        assertEquals("标题", page.title)
        assertTrue(page.text.contains("第一段内容"))
        assertFalse("script 内容不该进正文", page.text.contains("console.log"))
        assertFalse("导航不该进正文", page.text.contains("首页"))
        assertFalse("页脚不该进正文", page.text.contains("版权信息"))
        assertFalse(page.looksEmpty)
    }

    @Test
    fun `SPA 空壳被判定为需要渲染`() {
        // 典型的靠 JS 渲染的页面：初始 HTML 里只有挂载点。
        val html = """
            <html><head><title>App</title></head>
            <body><div id="app"></div>
            <script src="/main.js"></script></body></html>
        """.trimIndent()

        val page = PageFetch.extractFromHtml(html, "https://spa.example.com", 10_000)

        assertTrue("抽不出正文时必须能识别出来，否则永远不会走浏览器那条路", page.looksEmpty)
    }

    @Test
    fun `只有两句话的真文章不该被当成空壳`() {
        // 这条是判据的边界：正文短不等于没渲染完。没有脚本的短页面就是它本来的样子，
        // 送去浏览器渲染只会白等十几秒，还把一句正确的话换成一个超时。
        val html = "<html><body><article><p>今日停服维护。</p></article></body></html>"

        val page = PageFetch.extractFromHtml(html, "https://example.com/notice", 10_000)

        assertFalse(page.looksEmpty)
    }

    @Test
    fun `没有 article 时退到 body 取正文`() {
        val html = "<html><body><p>" + "内容".repeat(200) + "</p></body></html>"

        val page = PageFetch.extractFromHtml(html, "https://example.com", 10_000)

        assertFalse(page.looksEmpty)
    }

    @Test
    fun `超过上限时截断并标记`() {
        val html = "<html><body><p>" + "x".repeat(5000) + "</p></body></html>"

        val page = PageFetch.extractFromHtml(html, "https://example.com", maxChars = 1000)

        assertTrue(page.truncated)
        assertEquals(1000, page.text.length)
    }
}

/**
 * 读屏策略：顺序、选择器、判空。
 *
 * 这条路径的失败模式很隐蔽：空结果被当成成功时，模型会得到一段空白，
 * 然后据此回答"没搜到"——而实际上只是第一个引擎被验证码拦了。
 */
class ReadScreenPolicyTest {

    @Test
    fun `引擎顺序是 Bing 优先且带各自的结果容器`() {
        val engines = WebSearch.engines("kotlin")

        assertEquals(listOf("bing", "baidu"), engines.map { it.name })
        assertEquals("#b_results", engines[0].selector)
        assertEquals("#content_left", engines[1].selector)
    }

    @Test
    fun `查询被正确编码进地址`() {
        val url = WebSearch.engines("kotlin 协程")[0].url

        assertTrue("空格要编码，否则地址会被截断", url.contains("kotlin+%E5%8D%8F%E7%A8%8B"))
    }

    @Test
    fun `空文本和过短文本都不可接受`() {
        assertFalse("完全空 = 没读到", WebSearch.acceptable(""))
        assertFalse("几个字 = 被验证码拦了", WebSearch.acceptable("请完成验证"))
        assertTrue("有实质内容才当结果", WebSearch.acceptable("结果行 ".repeat(30)))
    }
}
