package ev.buan.plugins.ev.ui.ui

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the model-provider page's editing rules: which model the chat is using, what a draft may be
 * saved as, how a `/models` refresh merges, and what "set as current" does to the other providers.
 */
class ModelProviderStateTest {

    @Test
    fun `no model service is the missing state`() {
        assertEquals(ProvidersState.Missing, readProviders { null })
    }

    @Test
    fun `the provider list loads as stored`() {
        val providers = listOf(provider("openai", name = "OpenAI"), provider("custom-1"))

        assertEquals(ProvidersState.Loaded(providers), readProviders { FakeService(providers) })
    }

    @Test
    fun `a failing lookup or read is reported instead of crashing`() {
        assertEquals(
            ProvidersState.Failed("目录坏了"),
            readProviders { error("目录坏了") },
        )

        val failing = object : ModelProviderService {
            override fun providers(): List<ModelProvider> = error("文件无法解析")
            override fun saveProvider(provider: ModelProvider) = Unit
            override fun deleteProvider(id: String) = Unit
            override fun resetProvider(id: String) = Unit
            override suspend fun fetchRemoteModels(providerId: String): List<ModelDescriptor> = error("unused")
            override suspend fun testConnection(providerId: String): ModelTestResult = error("unused")
        }
        assertEquals(ProvidersState.Failed("文件无法解析"), readProviders { failing })
    }

    @Test
    fun `search matches name, id and base URL`() {
        val providers = listOf(
            provider("deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1"),
            provider("custom-1", name = "本地网关", baseUrl = "http://192.168.1.5:8080/v1"),
        )

        assertEquals(listOf("deepseek"), filterProviders(providers, "deep").map { it.id })
        assertEquals(listOf("custom-1"), filterProviders(providers, "192.168").map { it.id })
        assertEquals(listOf("custom-1"), filterProviders(providers, "网关").map { it.id })
        assertEquals(providers, filterProviders(providers, "   "))
    }

    @Test
    fun `the current model is the first selected one in provider order`() {
        val providers = listOf(
            provider("openai", models = listOf(model("gpt-4o")), selected = "gpt-4o"),
            provider("deepseek", models = listOf(model("deepseek-chat")), selected = "deepseek-chat"),
        )

        assertEquals(ModelRef("openai", "gpt-4o"), currentModel(providers))
    }

    @Test
    fun `a provider without a selection falls back to its first model`() {
        val providers = listOf(provider("custom-1", models = listOf(model("a"), model("b"))))

        assertEquals(ModelRef("custom-1", "a"), currentModel(providers))
    }

    @Test
    fun `disabled providers and dangling selections do not become current`() {
        val disabled = provider("openai", models = listOf(model("gpt-4o")), selected = "gpt-4o", enabled = false)
        val dangling = provider("deepseek", models = listOf(model("deepseek-chat")), selected = "gone")

        assertEquals(ModelRef("deepseek", "deepseek-chat"), currentModel(listOf(disabled, dangling)))
        assertNull(currentModel(listOf(disabled)))
        assertNull(currentModel(emptyList()))
    }

    @Test
    fun `provider summaries carry the library markers`() {
        val current = ModelRef("deepseek", "deepseek-chat")
        val builtIn = provider(
            "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf(model("deepseek-chat")),
            selected = "deepseek-chat",
            builtIn = true,
            apiKey = "sk-x",
        )
        val custom = provider("custom-1", baseUrl = "", models = emptyList(), enabled = false)

        val builtInSummary = providerSummary(builtIn, current)
        assertTrue(builtInSummary, builtInSummary.contains("内置"))
        assertTrue(builtInSummary, builtInSummary.contains("使用中"))
        assertTrue(builtInSummary, builtInSummary.contains("已配置 Key"))
        assertTrue(builtInSummary, builtInSummary.contains("https://api.deepseek.com/v1"))
        assertTrue(builtInSummary, builtInSummary.contains("1 个模型"))

        val customSummary = providerSummary(custom, current)
        assertTrue(customSummary, customSummary.contains("自定义"))
        assertTrue(customSummary, customSummary.contains("已禁用"))
        assertTrue(customSummary, customSummary.contains("未配置 Key"))
        assertTrue(customSummary, customSummary.contains("未填写 Base URL"))
        assertFalse(customSummary, customSummary.contains("使用中"))
    }

    @Test
    fun `model summaries show the current pick and the custom marker`() {
        val current = ModelRef("p1", "m1")
        val provider = provider("p1", models = listOf(model("m1")), selected = "m2")

        val currentSummary = modelSummary(model("m1", contextWindow = "128K", thinking = true), provider, current)
        assertTrue(currentSummary, currentSummary.contains("128K"))
        assertTrue(currentSummary, currentSummary.contains("支持思考"))
        assertTrue(currentSummary, currentSummary.contains("当前使用"))

        val selectedOnly = modelSummary(model("m2"), provider, current)
        assertTrue(selectedOnly, selectedOnly.contains("已选中"))

        val custom = modelSummary(model("m3", custom = true), provider, current)
        assertTrue(custom, custom.contains("自定义"))
        assertFalse(custom, custom.contains("已选中"))
    }

    @Test
    fun `parameter summaries say default when nothing is set`() {
        assertEquals("默认参数", parametersSummary(ModelParameters()))
        assertEquals(
            "temperature 0.7 · topP 0.9 · maxTokens 1024",
            parametersSummary(ModelParameters(temperature = 0.7, topP = 0.9, maxTokens = 1024)),
        )
    }

    @Test
    fun `a new custom provider is blank but addressable`() {
        val first = newCustomProvider()
        val second = newCustomProvider()

        assertTrue(first.id, first.id.startsWith("custom-"))
        assertTrue(first.id != second.id)
        assertEquals("", first.name)
        assertEquals("", first.baseUrl)
        assertTrue(first.enabled)
        assertFalse(first.builtIn)
    }

    @Test
    fun `a draft needs a name and an http address before it can be saved`() {
        assertEquals("名称不能为空", providerDraftError(provider("p1", name = "", baseUrl = "https://x.dev")))
        assertEquals("Base URL 不能为空", providerDraftError(provider("p1", baseUrl = "")))
        assertEquals(
            "Base URL 必须以 http:// 或 https:// 开头",
            providerDraftError(provider("p1", baseUrl = "api.example.com")),
        )
        assertEquals(
            "Base URL 缺少主机名",
            providerDraftError(provider("p1", baseUrl = "https:///v1")),
        )
        assertNull(providerDraftError(provider("p1", baseUrl = "https://api.example.com/v1")))
        assertNull(providerDraftError(provider("p1", baseUrl = "http://192.168.1.5:8080/v1")))
    }

    @Test
    fun `header rows round-trip and drop blank names`() {
        val rows = listOf(
            HeaderRow("X-Org", " ev "),
            HeaderRow("  ", "ignored"),
            HeaderRow("X-Org", "last wins"),
            HeaderRow("Accept-Language", "zh"),
        )

        assertEquals(listOf("X-Org", "Accept-Language"), headerMap(rows).keys.toList())
        assertEquals("last wins", headerMap(rows)["X-Org"])
        assertEquals("zh", headerMap(rows)["Accept-Language"])
        assertEquals(
            listOf(HeaderRow("X-Org", "ev"), HeaderRow("Accept", "json")),
            headerRows(linkedMapOf("X-Org" to "ev", "Accept" to "json")),
        )
    }

    @Test
    fun `selecting a model makes it the only selection`() {
        val providers = listOf(
            provider("openai", models = listOf(model("a")), selected = "a"),
            provider("deepseek", models = listOf(model("b"), model("c"))),
            provider("custom-1", models = listOf(model("d"))),
        )

        val changes = selectCurrentModel(providers, "deepseek", "c")

        assertEquals(2, changes.size)
        assertEquals("c", changes.first { it.id == "deepseek" }.selectedModelId)
        assertNull(changes.first { it.id == "openai" }.selectedModelId)
        // A provider that never had a pick is not rewritten.
        assertTrue(changes.none { it.id == "custom-1" })
        // Selecting the current pick again writes nothing.
        assertTrue(selectCurrentModel(providers, "openai", "a").isEmpty())
    }

    @Test
    fun `models can be added, replaced and removed`() {
        val models = listOf(model("a"), model("b"))

        assertEquals(listOf("a", "b", "c"), upsertModel(models, model("c")).map { it.id })
        // Replacing by id keeps the position and takes the new display name.
        assertEquals(listOf("a", "B2"), upsertModel(models, model("b", displayName = "B2")).map { it.displayName })

        val provider = provider("p1", models = models, selected = "a")
        assertEquals(listOf("b"), removeModel(provider, "a").models.map { it.id })
        assertNull(removeModel(provider, "a").selectedModelId)
        assertEquals("a", removeModel(provider, "b").selectedModelId)
    }

    @Test
    fun `a remote refresh keeps custom models and the surviving selection`() {
        val provider = provider(
            "p1",
            models = listOf(model("old"), model("hand-made", custom = true)),
            selected = "hand-made",
        )

        val merged = mergeFetchedModels(provider, listOf(model("remote-1"), model("hand-made")))

        assertEquals(listOf("remote-1", "hand-made"), merged.models.map { it.id })
        assertEquals("hand-made", merged.selectedModelId)
        // The refreshed entries are provider-owned again, even if the server also lists a custom id.
        assertEquals(listOf(false, false), merged.models.map { it.custom })

        val dropped = mergeFetchedModels(provider, listOf(model("remote-1")))
        assertEquals(listOf("remote-1", "hand-made"), dropped.models.map { it.id })
        assertNull(mergeFetchedModels(provider.copy(selectedModelId = "old"), listOf(model("remote-1"))).selectedModelId)
    }

    @Test
    fun `parameter fields accept blanks, numbers and reject typos`() {
        assertEquals(
            ParametersParse.Valid(ModelParameters()),
            parseModelParameters("", "  ", ""),
        )
        assertEquals(
            ParametersParse.Valid(ModelParameters(temperature = 0.7, topP = 0.9, maxTokens = 1024)),
            parseModelParameters("0.7", "0.9", "1024"),
        )
        assertTrue(parseModelParameters("hot", "", "") is ParametersParse.Invalid)
        assertTrue(parseModelParameters("2.5", "", "") is ParametersParse.Invalid)
        assertTrue(parseModelParameters("", "1.5", "") is ParametersParse.Invalid)
        assertTrue(parseModelParameters("", "", "0") is ParametersParse.Invalid)
        assertTrue(parseModelParameters("", "", "1.5") is ParametersParse.Invalid)
    }

    @Test
    fun `a new model needs an id without spaces and a display name`() {
        assertEquals("Model ID 不能为空", modelDraftError("名字", " "))
        assertEquals("Model ID 不能包含空格", modelDraftError("名字", "deep seek"))
        assertEquals("展示名不能为空", modelDraftError(" ", "deepseek-chat"))
        assertNull(modelDraftError("DeepSeek Chat", "deepseek-chat"))
    }

    @Test
    fun `model search matches name, id and context window`() {
        val models = listOf(
            model("deepseek-chat", displayName = "DeepSeek Chat", contextWindow = "128K"),
            model("gpt-4o", displayName = "GPT-4o"),
        )

        assertEquals(listOf("deepseek-chat"), filterModels(models, "chat").map { it.id })
        assertEquals(listOf("gpt-4o"), filterModels(models, "GPT").map { it.id })
        assertEquals(listOf("deepseek-chat"), filterModels(models, "128k").map { it.id })
        assertEquals(models, filterModels(models, ""))
    }

    private fun provider(
        id: String,
        name: String = "Provider $id",
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "",
        enabled: Boolean = true,
        builtIn: Boolean = false,
        models: List<ModelDescriptor> = emptyList(),
        selected: String? = null,
    ) = ModelProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        enabled = enabled,
        builtIn = builtIn,
        models = models,
        selectedModelId = selected,
    )

    private fun model(
        id: String,
        displayName: String = id,
        contextWindow: String? = null,
        thinking: Boolean = false,
        custom: Boolean = false,
    ) = ModelDescriptor(
        id = id,
        displayName = displayName,
        contextWindow = contextWindow,
        supportsThinking = thinking,
        custom = custom,
    )
}

private class FakeService(private val providers: List<ModelProvider>) : ModelProviderService {
    override fun providers(): List<ModelProvider> = providers

    override fun saveProvider(provider: ModelProvider) = Unit

    override fun deleteProvider(id: String) = Unit

    override fun resetProvider(id: String) = Unit

    override suspend fun fetchRemoteModels(providerId: String): List<ModelDescriptor> = emptyList()

    override suspend fun testConnection(providerId: String): ModelTestResult =
        ModelTestResult(success = true, message = "ok")
}
