package ev.buan.plugins.agent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure `/models` interpretation and `testConnection` message building. */
class ProviderProbesTest {

    @Test
    fun `remote model list maps ids, display names, context and thinking support`() {
        val models = ProviderProbes.parseRemoteModels(
            """
            {
              "object": "list",
              "data": [
                {"id": "m1", "name": "Model One", "context_length": 128000, "supports_thinking": true},
                {"id": "m2", "display_name": "Model Two", "context_window": 8192},
                {"id": "m1"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("m1", "m2"), models?.map { it.id })
        val first = models!![0]
        assertEquals("Model One", first.displayName)
        assertEquals("128000", first.contextWindow)
        assertTrue(first.supportsThinking)
        assertFalse(first.custom, "fetched models are not user-added ones")
        assertEquals("Model Two", models[1].displayName)
        assertEquals("8192", models[1].contextWindow)
    }

    @Test
    fun `missing data array or non-json is not a model list`() {
        assertNull(ProviderProbes.parseRemoteModels("""{"object":"list"}"""))
        assertNull(ProviderProbes.parseRemoteModels("not json"))
        assertNull(ProviderProbes.parseRemoteModels("[]"))
    }

    @Test
    fun `test result reports the model count on success`() {
        val result = ProviderProbes.testResult(
            ProviderResponse(200, """{"data":[{"id":"m1"},{"id":"m2"}]}"""),
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("2"), result.message)
    }

    @Test
    fun `test result accepts a non-standard body but says so`() {
        val result = ProviderProbes.testResult(ProviderResponse(200, "plain text"))

        assertTrue(result.success)
        assertTrue(result.message.contains("不是标准"), result.message)
    }

    @Test
    fun `test result surfaces the provider error message on failure`() {
        val result = ProviderProbes.testResult(
            ProviderResponse(401, """{"error":{"message":"invalid api key"}}"""),
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("401"), result.message)
        assertTrue(result.message.contains("invalid api key"), result.message)
    }

    @Test
    fun `test result falls back to the raw body on a plain-text failure`() {
        val result = ProviderProbes.testResult(ProviderResponse(500, "upstream exploded"))

        assertFalse(result.success)
        assertTrue(result.message.contains("upstream exploded"), result.message)
    }
}
