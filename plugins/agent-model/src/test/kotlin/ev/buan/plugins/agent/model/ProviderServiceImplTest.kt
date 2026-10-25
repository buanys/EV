package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelProvider
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Service-level tests over the in-memory repository and a recording transport: what gets persisted,
 * what delete/reset mean, and what the two network probes send and report.
 */
class ProviderServiceImplTest {

    private val catalog = listOf(
        builtin("alpha", "https://alpha.test/v1"),
        builtin("beta", "https://beta.test/v1"),
    )
    private val repository = FakeProviderRepository(catalog = catalog)
    private val network = RecordingNetworkGate()
    private val transport = RecordingTransport()
    private val service = ProviderServiceImpl(repository, network, transport, catalog)

    @Test
    fun `saveProvider persists only the changed providers and providers merges the catalog`() {
        service.saveProvider(
            ModelProvider(
                id = "alpha",
                name = "Alpha",
                baseUrl = "https://alpha.test/v1",
                apiKey = "sk-alpha",
                enabled = true,
                models = listOf(ModelDescriptor("m1")),
                selectedModelId = "m1",
            ),
        )

        val alpha = service.providers().first { it.id == "alpha" }
        assertEquals("sk-alpha", alpha.apiKey)
        assertTrue(alpha.enabled)
        assertEquals(listOf("m1"), alpha.models.map { it.id })
        assertEquals(listOf("alpha"), repository.stored.map { it.id }, "untouched beta is not written")
    }

    @Test
    fun `saveProvider appends a custom provider`() {
        service.saveProvider(custom("local"))

        assertEquals(listOf("alpha", "beta", "local"), service.providers().map { it.id })
        assertEquals(listOf("local"), repository.stored.map { it.id })
    }

    @Test
    fun `deleteProvider resets a built-in instead of removing it`() {
        service.saveProvider(
            ModelProvider(
                id = "alpha",
                name = "Alpha proxy",
                baseUrl = "https://proxy.test/v1",
                apiKey = "sk-alpha",
                enabled = true,
                models = listOf(ModelDescriptor("m1")),
            ),
        )
        service.deleteProvider("alpha")

        val alpha = service.providers().first { it.id == "alpha" }
        assertEquals("https://alpha.test/v1", alpha.baseUrl)
        assertEquals("", alpha.apiKey)
        assertFalse(alpha.enabled, "keyless built-in is disabled")
        assertEquals(2, service.providers().size)
    }

    @Test
    fun `deleteProvider removes a custom provider`() {
        service.saveProvider(custom("local"))
        service.deleteProvider("local")

        assertTrue(service.providers().none { it.id == "local" })
    }

    @Test
    fun `resetProvider restores the catalog definition`() {
        service.saveProvider(
            ModelProvider(
                id = "alpha",
                name = "Edited",
                baseUrl = "https://proxy.test/v1",
                apiKey = "sk",
                enabled = true,
            ),
        )
        service.resetProvider("alpha")

        assertEquals(catalog[0], service.providers().first { it.id == "alpha" })
    }

    @Test
    fun `fetchRemoteModels calls GET models with merged headers and parses the list`() {
        seedProvider("alpha")
        transport.response = ProviderResponse(
            200,
            """{"data":[{"id":"m1","name":"Model One","context_length":128000},{"id":"m2"}]}""",
        )

        val models = runBlocking { service.fetchRemoteModels("alpha") }

        assertEquals(listOf("m1", "m2"), models.map { it.id })
        assertEquals("Model One", models[0].displayName)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://alpha.test/v1/models", request.url)
        assertEquals("Bearer sk-alpha", request.headers["Authorization"])
        assertEquals("1", request.headers["X-EV"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals(1, network.checks)
    }

    @Test
    fun `fetchRemoteModels throws a readable error on an HTTP failure`() {
        seedProvider("alpha")
        transport.response = ProviderResponse(401, """{"error":{"message":"invalid api key"}}""")

        val error = assertFailsWith<ProviderRequestException> {
            runBlocking { service.fetchRemoteModels("alpha") }
        }

        assertTrue(error.message.orEmpty().contains("401"), error.message)
        assertTrue(error.message.orEmpty().contains("invalid api key"), error.message)
    }

    @Test
    fun `fetchRemoteModels rejects an unknown provider without a request`() {
        assertFailsWith<ProviderRequestException> {
            runBlocking { service.fetchRemoteModels("missing") }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `testConnection reports success with the model count`() {
        seedProvider("alpha")
        transport.response = ProviderResponse(200, """{"data":[{"id":"m1"},{"id":"m2"},{"id":"m3"}]}""")

        val result = runBlocking { service.testConnection("alpha") }

        assertTrue(result.success)
        assertTrue(result.message.contains("3"), result.message)
        assertEquals("https://alpha.test/v1/models", transport.requests.single().url)
    }

    @Test
    fun `testConnection reports failure instead of throwing`() {
        seedProvider("alpha")
        transport.response = ProviderResponse(401, """{"error":{"message":"bad key"}}""")
        val rejected = runBlocking { service.testConnection("alpha") }
        assertFalse(rejected.success)
        assertTrue(rejected.message.contains("bad key"), rejected.message)

        transport.failure = IOException("boom")
        val broken = runBlocking { service.testConnection("alpha") }
        assertFalse(broken.success)
        assertTrue(broken.message.contains("boom"), broken.message)
    }

    @Test
    fun `testConnection reports an unknown provider without a request`() {
        val result = runBlocking { service.testConnection("missing") }

        assertFalse(result.success)
        assertTrue(result.message.contains("missing"), result.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a provider without a base url fails without a request`() {
        service.saveProvider(
            ModelProvider(
                id = "broken",
                name = "Broken",
                baseUrl = "",
                apiKey = "sk",
                enabled = true,
                models = listOf(ModelDescriptor("m1")),
            ),
        )

        val error = assertFailsWith<ProviderRequestException> {
            runBlocking { service.fetchRemoteModels("broken") }
        }
        assertTrue(error.message.orEmpty().contains("未配置接口地址"), error.message)

        val result = runBlocking { service.testConnection("broken") }
        assertFalse(result.success)
        assertTrue(result.message.contains("未配置接口地址"), result.message)
        assertTrue(transport.requests.isEmpty())
    }

    private fun seedProvider(id: String) {
        service.saveProvider(
            ModelProvider(
                id = id,
                name = id,
                baseUrl = "https://$id.test/v1",
                apiKey = "sk-$id",
                enabled = true,
                headers = mapOf("X-EV" to "1"),
                models = listOf(ModelDescriptor("m1")),
                selectedModelId = "m1",
            ),
        )
    }

    private fun builtin(id: String, baseUrl: String): ModelProvider = ModelProvider(
        id = id,
        name = id,
        baseUrl = baseUrl,
        apiKey = "",
        enabled = false,
        builtIn = true,
    )

    private fun custom(id: String): ModelProvider = ModelProvider(
        id = id,
        name = id,
        baseUrl = "https://$id.test/v1",
        apiKey = "sk-$id",
        enabled = true,
    )
}
