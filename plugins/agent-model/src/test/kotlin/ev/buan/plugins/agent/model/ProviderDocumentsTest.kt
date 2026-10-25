package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelEndpointMode
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure document tests: codec, catalog merge, legacy migration, upsert/delete/reset, stored subset,
 * header merging and the selected-first ordering `ModelService.models` relies on.
 */
class ProviderDocumentsTest {

    private val catalog = listOf(
        builtin("alpha", "Alpha", "https://alpha.test/v1"),
        builtin("beta", "Beta", "https://beta.test/v1"),
    )

    // ------------------------------------------------------------------ codec

    @Test
    fun `encode and decode roundtrip every provider and model field`() {
        val provider = ModelProvider(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-deep",
            endpointMode = ModelEndpointMode.CHAT_COMPLETIONS,
            headers = linkedMapOf("X-Title" to "EV", "Authorization" to "Bearer custom"),
            enabled = true,
            builtIn = true,
            models = listOf(
                ModelDescriptor(
                    id = "deepseek-chat",
                    displayName = "DeepSeek Chat",
                    contextWindow = "128K",
                    supportsThinking = true,
                    parameters = ModelParameters(temperature = 0.7, topP = 0.9, maxTokens = 2048),
                    custom = false,
                ),
                ModelDescriptor(
                    id = "my-model",
                    displayName = "My Model",
                    contextWindow = "8K",
                    parameters = ModelParameters(topP = 0.5),
                    custom = true,
                ),
            ),
            selectedModelId = "deepseek-chat",
        )
        val custom = ModelProvider(
            id = "local",
            name = "Local",
            baseUrl = "http://127.0.0.1:11434/v1",
            enabled = true,
        )

        val decoded = ProviderDocuments.decode(ProviderDocuments.encode(listOf(provider, custom)))

        assertEquals(listOf(provider, custom), decoded)
    }

    @Test
    fun `decode accepts a bare provider array and ignores unknown fields`() {
        val decoded = ProviderDocuments.decode(
            """[{"id":"x","name":"X","baseUrl":"https://x.test/v1","future":1}]""",
        )

        assertEquals(1, decoded?.size)
        assertEquals("x", decoded?.single()?.id)
        assertEquals("X", decoded?.single()?.name)
        assertTrue(decoded?.single()?.enabled == true, "enabled defaults to true")
    }

    @Test
    fun `decode skips entries without an id and malformed models`() {
        val decoded = ProviderDocuments.decode(
            """
            [
              {"name":"no id"},
              {"id":"ok","name":"Ok","baseUrl":"https://ok.test/v1","models":[
                {"id":"m1"},
                {"displayName":"no id"},
                {"id":"m2"}
              ]}
            ]
            """.trimIndent(),
        )

        assertEquals(1, decoded?.size)
        assertEquals(listOf("m1", "m2"), decoded?.single()?.models?.map { it.id })
    }

    @Test
    fun `decode returns null for non-json and documents without providers`() {
        assertNull(ProviderDocuments.decode("not json"))
        assertNull(ProviderDocuments.decode("""{"version":1}"""))
        assertEquals(emptyList(), ProviderDocuments.decode("[]"))
    }

    // ------------------------------------------------------------------ merge

    @Test
    fun `merge keeps catalog order, applies overrides and forces catalog facts`() {
        val saved = listOf(
            ModelProvider(
                id = "beta",
                name = "Beta via proxy",
                baseUrl = "https://beta.proxy/v1",
                apiKey = "sk-beta",
                enabled = true,
                builtIn = false,
                models = listOf(descriptor("b1")),
                selectedModelId = "b1",
            ),
            // A stored built-in without a key must come back disabled, whatever the file says.
            ModelProvider(
                id = "alpha",
                name = "Alpha",
                baseUrl = "https://alpha.test/v1",
                apiKey = "",
                enabled = true,
                builtIn = true,
            ),
        )

        val merged = ProviderDocuments.merge(saved, catalog)

        assertEquals(listOf("alpha", "beta"), merged.map { it.id })
        val alpha = merged[0]
        assertTrue(alpha.builtIn)
        assertFalse(alpha.enabled, "keyless built-in must be disabled")
        val beta = merged[1]
        assertTrue(beta.builtIn, "builtIn is a catalog fact, not a stored one")
        assertTrue(beta.enabled)
        assertEquals("https://beta.proxy/v1", beta.baseUrl)
        assertEquals(listOf("b1"), beta.models.map { it.id })
    }

    @Test
    fun `merge appends custom providers and drops duplicates`() {
        val saved = listOf(
            custom("local", name = "Local"),
            custom("local", name = "Local duplicate"),
            custom("another"),
        )

        val merged = ProviderDocuments.merge(saved, catalog)

        assertEquals(listOf("alpha", "beta", "local", "another"), merged.map { it.id })
        assertEquals("Local", merged.first { it.id == "local" }.name)
        assertTrue(merged.none { it.builtIn && it.id !in setOf("alpha", "beta") })
    }

    // ------------------------------------------------------------------ legacy migration

    @Test
    fun `migration turns the single config into one selected custom provider`() {
        val migrated = ProviderDocuments
            .migrateLegacy(ModelConfig(" https://api.openai.com/v1/ ", "sk-old", "gpt-4o-mini "))
            .single()

        assertEquals("default", migrated.id)
        assertEquals("默认", migrated.name)
        assertEquals("https://api.openai.com/v1", migrated.baseUrl)
        assertEquals("sk-old", migrated.apiKey)
        assertTrue(migrated.enabled)
        assertFalse(migrated.builtIn)
        assertEquals(listOf("gpt-4o-mini"), migrated.models.map { it.id })
        assertTrue(migrated.models.single().custom)
        assertEquals("gpt-4o-mini", migrated.selectedModelId)
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun `upsert replaces by id or appends, deriving builtIn from the catalog`() {
        val base = ProviderDocuments.merge(emptyList(), catalog)

        val replaced = ProviderDocuments.upsert(
            base,
            ModelProvider(
                id = "alpha",
                name = "Alpha proxy",
                baseUrl = "https://proxy.test/v1",
                apiKey = "sk",
                builtIn = false,
                models = listOf(descriptor("m")),
            ),
            catalog,
        )
        assertEquals("Alpha proxy", replaced[0].name)
        assertTrue(replaced[0].builtIn)
        assertEquals(2, replaced.size)

        val appended = ProviderDocuments.upsert(replaced, custom("mine"), catalog)
        assertEquals(listOf("alpha", "beta", "mine"), appended.map { it.id })
        assertFalse(appended.last().builtIn)

        assertFailsWith<IllegalArgumentException> {
            ProviderDocuments.upsert(appended, custom("   "), catalog)
        }
    }

    @Test
    fun `delete removes the id and reset restores the catalog default`() {
        val merged = ProviderDocuments.merge(listOf(custom("mine")), catalog)
        assertEquals(listOf("alpha", "beta"), ProviderDocuments.delete(merged, "mine").map { it.id })

        val edited = ProviderDocuments.upsert(
            merged,
            ModelProvider(
                id = "alpha",
                name = "Edited",
                baseUrl = "https://elsewhere/v1",
                apiKey = "sk",
                enabled = true,
                models = listOf(descriptor("m")),
            ),
            catalog,
        )
        assertEquals("Edited", edited[0].name)

        val reset = ProviderDocuments.reset(edited, "alpha", catalog)
        assertEquals(catalog[0], reset[0])
    }

    @Test
    fun `storable keeps edits and custom entries but omits untouched catalog defaults`() {
        val untouched = ProviderDocuments.merge(emptyList(), catalog)
        assertEquals(emptyList(), ProviderDocuments.storable(untouched, catalog))

        val edited = ProviderDocuments.upsert(
            untouched,
            ModelProvider(
                id = "alpha",
                name = "Alpha",
                baseUrl = "https://alpha.test/v1",
                apiKey = "sk",
                enabled = true,
            ),
            catalog,
        ) + custom("mine")

        assertEquals(listOf("alpha", "mine"), ProviderDocuments.storable(edited, catalog).map { it.id })
    }

    // ------------------------------------------------------------------ selection / headers

    @Test
    fun `selection puts the selected model first and skips disabled providers`() {
        val providers = listOf(
            custom("p1", models = listOf(descriptor("m1"), descriptor("m2")), selected = "m2"),
            custom("p2", models = listOf(descriptor("x1"))),
            custom("p3", enabled = false, models = listOf(descriptor("g1"))),
        )

        assertEquals(
            listOf(ModelRef("p1", "m2"), ModelRef("p1", "m1"), ModelRef("p2", "x1")),
            ProviderDocuments.selectedFirst(providers),
        )
    }

    @Test
    fun `selection tolerates a stale selected id and empty lists`() {
        val providers = listOf(
            custom("p1", models = listOf(descriptor("m1")), selected = "gone"),
            custom("p2"),
        )

        assertEquals(listOf(ModelRef("p1", "m1")), ProviderDocuments.selectedFirst(providers))
        assertEquals(emptyList(), ProviderDocuments.selectedFirst(emptyList()))
    }

    @Test
    fun `request headers merge custom headers, api key and accept`() {
        val provider = custom("p", key = "sk-1", headers = linkedMapOf("X-EV" to "1"))

        assertEquals(
            mapOf("X-EV" to "1", "Authorization" to "Bearer sk-1", "Accept" to "application/json"),
            ProviderDocuments.requestHeaders(provider),
        )
        val explicit = provider.copy(headers = mapOf("Authorization" to "Bearer explicit"))
        assertEquals("Bearer explicit", ProviderDocuments.requestHeaders(explicit)["Authorization"])
        assertFalse("Authorization" in ProviderDocuments.requestHeaders(provider.copy(apiKey = "")))
    }

    @Test
    fun `base url validation describes why a provider cannot be called`() {
        assertEquals("未配置接口地址", ProviderDocuments.baseUrlError("  "))
        assertEquals("接口地址必须以 http:// 或 https:// 开头", ProviderDocuments.baseUrlError("api.test/v1"))
        assertEquals("接口地址缺少主机名", ProviderDocuments.baseUrlError("https:///v1"))
        assertNull(ProviderDocuments.baseUrlError("https://api.test/v1"))
        assertNull(ProviderDocuments.baseUrlError("http://127.0.0.1:11434/v1"))
    }

    // ------------------------------------------------------------------ helpers

    private fun builtin(id: String, name: String, baseUrl: String): ModelProvider = ModelProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = "",
        enabled = false,
        builtIn = true,
    )

    private fun custom(
        id: String,
        name: String = id,
        enabled: Boolean = true,
        key: String = "sk-$id",
        headers: Map<String, String> = emptyMap(),
        models: List<ModelDescriptor> = emptyList(),
        selected: String? = null,
    ): ModelProvider = ModelProvider(
        id = id,
        name = name,
        baseUrl = "https://$id.test/v1",
        apiKey = key,
        headers = headers,
        enabled = enabled,
        builtIn = false,
        models = models,
        selectedModelId = selected,
    )

    private fun descriptor(id: String): ModelDescriptor = ModelDescriptor(id = id)
}
