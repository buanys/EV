package ev.buan.plugins.agent.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏解析的边界。
 *
 * 这条规则每天都在被用户用，但错了只表现为"打开了一张错误页"，从日志里看不出是判断错了
 * 还是网络不通。所以把它单独钉在这里：哪些必须当地址、哪些必须当搜索。
 */
class BrowserUrlTest {

    @Test
    fun `bare host becomes https`() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com/a/b?c=1", normalizeUrl("example.com/a/b?c=1"))
    }

    @Test
    fun `explicit scheme is left alone`() {
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
        assertEquals("https://example.com", normalizeUrl("  https://example.com  "))
        assertEquals("about:blank", normalizeUrl("about:blank"))
    }

    @Test
    fun `localhost with port is an address not a search`() {
        // `localhost:8080` 里的冒号是端口，不是 scheme——这是 whitelist 而不是
        // "看起来像 scheme 就当 scheme" 的原因。
        assertEquals("https://localhost:8080", normalizeUrl("localhost:8080"))
    }

    @Test
    fun `ip address is an address`() {
        assertEquals("https://192.168.1.1", normalizeUrl("192.168.1.1"))
        assertEquals("https://192.168.1.1:8080/x", normalizeUrl("192.168.1.1:8080/x"))
    }

    @Test
    fun `words become a search`() {
        assertEquals(searchUrl("hello world"), normalizeUrl("hello world"))
        assertEquals(searchUrl("今天 天气"), normalizeUrl("今天 天气"))
    }

    @Test
    fun `single word without a dot becomes a search`() {
        assertEquals(searchUrl("zhihu"), normalizeUrl("zhihu"))
        assertEquals(searchUrl("flutter"), normalizeUrl("flutter"))
    }

    @Test
    fun `blank input has no target`() {
        assertNull(normalizeUrl(""))
        assertNull(normalizeUrl("   "))
    }

    @Test
    fun `search url is encoded`() {
        val url = searchUrl("中文 搜索")
        assertTrue(url.startsWith("https://cn.bing.com/search?q="))
        assertTrue("空格要被编码，否则 WebView 直接拒掉这个 URL", '+' in url || "%20" in url)
    }
}
