package ev.buan.plugins.agent.web

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the request parser and path confinement — no Android, no org.json. */
class HttpExchangeTest {

    @Test
    fun `a get request parses method path query and headers`() {
        val request = read(
            "GET /api/capabilities?token=abc&lang=zh HTTP/1.1\r\n" +
                "Host: 192.168.1.5:8765\r\n" +
                "X-EV-Token: abc\r\n" +
                "\r\n",
        )

        assertEquals("GET", request.method)
        assertEquals("/api/capabilities", request.path)
        assertEquals(mapOf("token" to "abc", "lang" to "zh"), request.query)
        assertEquals("abc", request.header("x-ev-token"))
        assertEquals(0, request.body.size)
    }

    @Test
    fun `a post request carries its body`() {
        val body = "{\"sessionId\":\"s1\",\"text\":\"你好\"}"
        val request = read(
            "POST /api/chat HTTP/1.1\r\n" +
                "Content-Length: ${body.encodeToByteArray().size}\r\n" +
                "\r\n" +
                body,
        )

        assertEquals("POST", request.method)
        assertEquals("/api/chat", request.path)
        assertEquals(body, request.body.decodeToString())
    }

    @Test
    fun `eof before any byte yields null`() {
        assertNull(HttpExchange.readRequest(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = HttpRequestException::class)
    fun `an oversized declared body is rejected`() {
        read("POST /api/chat HTTP/1.1\r\nContent-Length: 300000\r\n\r\n")
    }

    @Test(expected = HttpRequestException::class)
    fun `a malformed header line is rejected`() {
        read("GET / HTTP/1.1\r\nno colon here\r\n\r\n")
    }

    @Test
    fun `confinePath collapses dot segments and cannot escape root`() {
        assertEquals("/assets/app.js", HttpExchange.confinePath("/assets/./app.js"))
        assertEquals("/index.html", HttpExchange.confinePath("/assets/../index.html"))
        assertEquals("/index.html", HttpExchange.confinePath("/../../../etc/passwd/../../index.html"))
        assertEquals("/", HttpExchange.confinePath("/../../.."))
    }

    @Test
    fun `parseQuery decodes pairs and tolerates bare keys`() {
        assertEquals(
            mapOf("a" to "1", "b" to "两 个", "c" to ""),
            HttpExchange.parseQuery("a=1&b=%E4%B8%A4+%E4%B8%AA&c"),
        )
    }

    @Test
    fun `mimeOf maps web assets and falls back`() {
        assertEquals("text/html; charset=utf-8", HttpExchange.mimeOf("/index.html"))
        assertEquals("text/javascript; charset=utf-8", HttpExchange.mimeOf("/assets/index-abc.js"))
        assertEquals("font/woff2", HttpExchange.mimeOf("/font.woff2"))
        assertEquals("application/octet-stream", HttpExchange.mimeOf("/data.bin"))
    }

    @Test
    fun `response bytes carry the status line and content length`() {
        val bytes = HttpResponse.json(200, "{}").bytes().decodeToString()

        assertTrue(bytes.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(bytes.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertTrue(bytes.endsWith("\r\n\r\n{}"))
    }

    private fun read(raw: String): HttpRequest =
        HttpExchange.readRequest(ByteArrayInputStream(raw.encodeToByteArray()))
            ?: error("expected a request")
}
