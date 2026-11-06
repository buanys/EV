package ev.buan.plugins.agent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure codec/validation tests; nothing here touches `PluginStorage` or the network. */
class ModelConfigTest {

    @Test
    fun `encode and decode roundtrip normalizes whitespace and trailing slashes`() {
        val config = ModelConfig(
            baseUrl = "  https://api.openai.com/v1/ ",
            apiKey = " sk-secret ",
            model = " gpt-4o-mini ",
        )

        val decoded = ModelConfigCodec.decode(ModelConfigCodec.encode(config))

        assertEquals(ModelConfig("https://api.openai.com/v1", "sk-secret", "gpt-4o-mini"), decoded)
        assertEquals("https://api.openai.com/v1/chat/completions", config.endpoint)
    }

    @Test
    fun `blank api key is allowed for local OpenAI-compatible servers`() {
        val decoded = ModelConfigCodec.decode(
            """{"baseUrl":"http://127.0.0.1:11434/v1","apiKey":"","model":"qwen2.5"}""",
        )

        assertEquals(ModelConfig("http://127.0.0.1:11434/v1", "", "qwen2.5"), decoded)
        assertNull(decoded?.validationError())
    }

    @Test
    fun `unknown fields are ignored`() {
        val decoded = ModelConfigCodec.decode(
            """{"baseUrl":"https://example.test/v1","model":"m","temperature":0.2}""",
        )

        assertEquals(ModelConfig("https://example.test/v1", "", "m"), decoded)
    }

    @Test
    fun `non-http base urls are rejected`() {
        assertTrue(ModelConfig("ftp://example.test/v1", "", "m").validationError() != null)
        assertTrue(ModelConfig("api.openai.com/v1", "", "m").validationError() != null)
        assertNull(ModelConfigCodec.decode("""{"baseUrl":"ftp://example.test/v1","model":"m"}"""))
    }

    @Test
    fun `a base url without a host is rejected`() {
        assertTrue(ModelConfig("https:///v1", "", "m").validationError() != null)
        assertNull(ModelConfigCodec.decode("""{"baseUrl":"https:///v1","model":"m"}"""))
    }

    @Test
    fun `blank model is rejected`() {
        assertTrue(ModelConfig("https://example.test/v1", "", "   ").validationError() != null)
        assertNull(ModelConfigCodec.decode("""{"baseUrl":"https://example.test/v1","model":"   "}"""))
    }

    @Test
    fun `malformed or incomplete stored text decodes to null`() {
        assertNull(ModelConfigCodec.decode("not json"))
        assertNull(ModelConfigCodec.decode("[]"))
        assertNull(ModelConfigCodec.decode("""{"baseUrl":"https://example.test/v1"}"""))
        assertNull(ModelConfigCodec.decode("""{"model":"m"}"""))
    }
}
