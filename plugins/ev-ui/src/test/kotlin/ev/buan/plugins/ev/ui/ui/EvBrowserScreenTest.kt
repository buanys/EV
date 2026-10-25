package ev.buan.plugins.eta.ui.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins what the address bar accepts: a bare host is a URL, a bare host:port is not a scheme. */
class EtaBrowserScreenTest {

    @Test
    fun `blank input has no url`() {
        assertNull(normalizeUrl(""))
        assertNull(normalizeUrl("   "))
    }

    @Test
    fun `a bare host gets https and keeps its path`() {
        assertEquals("https://example.com/a?b=1", normalizeUrl(" example.com/a?b=1 "))
    }

    @Test
    fun `an explicit scheme is left alone`() {
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
        assertEquals("https://example.com/x", normalizeUrl("https://example.com/x"))
        assertEquals("about:blank", normalizeUrl("about:blank"))
    }

    @Test
    fun `scheme matching ignores case`() {
        assertEquals("HTTPS://EXAMPLE.COM", normalizeUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `a host with a port is a host, not a scheme`() {
        // The browser must not treat "localhost:" as the scheme of "localhost:8080".
        assertEquals("https://localhost:8080/x", normalizeUrl("localhost:8080/x"))
        assertEquals("https://127.0.0.1:8080", normalizeUrl("127.0.0.1:8080"))
    }
}
