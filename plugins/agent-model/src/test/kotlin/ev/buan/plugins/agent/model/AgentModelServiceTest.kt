package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelRef
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Routing tests for [AgentModelService]: which provider a [ModelRef] reaches, what parameters and
 * headers the request carries, and how failures become replies instead of exceptions.
 */
class AgentModelServiceTest {

    private val repository = FakeProviderRepository()
    private val network = RecordingNetworkGate()
    private val transport = RecordingTransport()
    private val service = AgentModelService(repository, network, transport)

    @Test
    fun `models lists the selected model first across enabled providers`() {
        seed(
            provider(
                id = "p1",
                models = listOf(ModelDescriptor("m1"), ModelDescriptor("m2")),
                selected = "m2",
            ),
            provider(id = "p2", models = listOf(ModelDescriptor("x1"))),
            provider(id = "p3", enabled = false, models = listOf(ModelDescriptor("g1"))),
        )

        assertEquals(
            listOf(ModelRef("p1", "m2"), ModelRef("p1", "m1"), ModelRef("p2", "x1")),
            service.models,
        )
    }

    @Test
    fun `models is empty when the repository cannot be read`() {
        repository.loadFailure = IOException("broken file")

        assertTrue(service.models.isEmpty())
    }

    @Test
    fun `complete posts to the routed provider with headers and model parameters`() {
        seed(
            provider(
                id = "p1",
                baseUrl = "https://p1.test/v1/",
                apiKey = "sk-p1",
                headers = mapOf("X-EV" to "1"),
                models = listOf(
                    ModelDescriptor(
                        id = "m1",
                        parameters = ModelParameters(temperature = 0.2, topP = 0.7, maxTokens = 1024),
                    ),
                ),
                selected = "m1",
            ),
        )
        transport.response = ProviderResponse(
            200,
            """{"model":"m1","choices":[{"message":{"role":"assistant","content":"pong"}}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}""",
        )

        val reply = runBlocking {
            service.complete(
                ModelRef("p1", "m1"),
                listOf(AgentMessage(AgentRole.USER, "ping")),
            )
        }

        assertEquals("pong", reply.message.content)
        assertEquals(ModelRef("p1", "m1"), reply.model)
        assertEquals(AgentUsage(promptTokens = 8, completionTokens = 2, totalTokens = 10), reply.usage)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://p1.test/v1/chat/completions", request.url)
        assertEquals("Bearer sk-p1", request.headers["Authorization"])
        assertEquals("1", request.headers["X-EV"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("m1", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.2, (body["temperature"] as JsonPrimitive).double, 1e-9)
        assertEquals(0.7, (body["top_p"] as JsonPrimitive).double, 1e-9)
        assertEquals(1024, (body["max_tokens"] as JsonPrimitive).int)
    }

    @Test
    fun `unknown provider, disabled provider and missing model become error replies`() {
        seed(provider(id = "off", enabled = false, models = listOf(ModelDescriptor("m1"))))

        val unknown = runBlocking { service.complete(ModelRef("ghost", "m1"), messages()) }
        assertTrue(unknown.message.content.contains("未找到模型提供商 ghost"), unknown.message.content)

        val disabled = runBlocking { service.complete(ModelRef("off", "m1"), messages()) }
        assertTrue(disabled.message.content.contains("已禁用"), disabled.message.content)

        seed(provider(id = "on", models = listOf(ModelDescriptor("m1"))))
        val missing = runBlocking { service.complete(ModelRef("on", "nope"), messages()) }
        assertTrue(missing.message.content.contains("未配置模型 nope"), missing.message.content)

        assertTrue(transport.requests.isEmpty())
        assertEquals(AgentEvent.Kind.ERROR, unknown.events.single().kind)
    }

    @Test
    fun `an HTTP failure becomes an error reply carrying the provider message`() {
        seed(provider(id = "p1", models = listOf(ModelDescriptor("m1"))))
        transport.response = ProviderResponse(401, """{"error":{"message":"bad key"}}""")

        val reply = runBlocking { service.complete(ModelRef("p1", "m1"), messages()) }

        assertTrue(reply.message.content.contains("HTTP 401"), reply.message.content)
        assertTrue(reply.message.content.contains("bad key"), reply.message.content)
        assertEquals(AgentEvent.Kind.ERROR, reply.events.single().kind)
    }

    @Test
    fun `a transport failure becomes an error reply`() {
        seed(provider(id = "p1", models = listOf(ModelDescriptor("m1"))))
        transport.failure = IOException("connection refused")

        val reply = runBlocking { service.complete(ModelRef("p1", "m1"), messages()) }

        assertTrue(reply.message.content.contains("connection refused"), reply.message.content)
    }

    @Test
    fun `a provider without a base url becomes an error reply without a request`() {
        seed(provider(id = "p1", baseUrl = "", models = listOf(ModelDescriptor("m1"))))

        val reply = runBlocking { service.complete(ModelRef("p1", "m1"), messages()) }

        assertTrue(reply.message.content.contains("未配置接口地址"), reply.message.content)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a revoked NETWORK grant propagates instead of becoming a reply`() {
        seed(provider(id = "p1", models = listOf(ModelDescriptor("m1"))))
        val denied = AgentModelService(
            repository,
            NetworkGate { throw SecurityException("PERMISSION_DENIED: NETWORK") },
            transport,
        )

        assertFailsWith<SecurityException> {
            runBlocking { denied.complete(ModelRef("p1", "m1"), messages()) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    private fun seed(vararg providers: ModelProvider) {
        repository.save(providers.toList())
    }

    private fun messages(): List<AgentMessage> = listOf(AgentMessage(AgentRole.USER, "hi"))

    private fun provider(
        id: String,
        baseUrl: String = "https://$id.test/v1",
        apiKey: String = "sk-$id",
        headers: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
        models: List<ModelDescriptor> = emptyList(),
        selected: String? = null,
    ): ModelProvider = ModelProvider(
        id = id,
        name = id,
        baseUrl = baseUrl,
        apiKey = apiKey,
        headers = headers,
        enabled = enabled,
        models = models,
        selectedModelId = selected,
    )
}
