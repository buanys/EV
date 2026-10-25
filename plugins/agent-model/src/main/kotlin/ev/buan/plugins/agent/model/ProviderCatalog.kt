package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelEndpointMode
import ev.buan.plugins.agent.api.ModelProvider

/**
 * The built-in provider directory.
 *
 * These are *defaults*, not records: the store merges them with whatever the user saved, so editing
 * one of these entries here changes every install that has not overridden that provider, while a
 * user-edited copy keeps its own values. Every entry is an OpenAI-compatible endpoint whose base
 * URL already ends in `/v1`; the model list starts empty because vendor catalogs change too often
 * to hardcode - the provider page either fetches `/models` or the user adds models by hand.
 *
 * Keyless built-ins stay disabled (see [ProviderDocuments.merge]): all of them are commercial
 * endpoints, and an enabled provider with no key would only produce 401s.
 */
internal object ProviderCatalog {
    val builtins: List<ModelProvider> = listOf(
        builtin("openai", "OpenAI", "https://api.openai.com/v1"),
        builtin("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        builtin("kimi", "Kimi", "https://api.moonshot.cn/v1"),
        builtin("mimo", "MiMo", "https://api.xiaomimimo.com/v1"),
        builtin("minimax", "MiniMax", "https://api.minimax.chat/v1"),
        builtin("stepfun", "StepFun", "https://api.stepfun.com/v1"),
        builtin("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1"),
        builtin("dashscope", "阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    )

    fun builtin(id: String): ModelProvider? = builtins.firstOrNull { it.id == id }

    private fun builtin(id: String, name: String, baseUrl: String): ModelProvider = ModelProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = "",
        endpointMode = ModelEndpointMode.CHAT_COMPLETIONS,
        headers = emptyMap(),
        enabled = false,
        builtIn = true,
        models = emptyList(),
        selectedModelId = null,
    )
}
