package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModelService] over the configured provider list.
 *
 * Routing is by [ModelRef.provider] (the provider id) and [ModelRef.model] (the wire model id):
 * both come from `models`, which lists enabled providers with the selected model first, so a
 * consumer that just takes the first entry gets the user's current pick.
 *
 * Every request carries the provider's merged headers (custom headers, then `Authorization` from
 * the API key) and the selected model's [ev.buan.plugins.agent.api.ModelParameters].
 *
 * Failure policy is unchanged from the single-provider version: configuration and transport
 * problems become an [AgentReply] with an [AgentEvent.Kind.ERROR] event so a brain that ignored
 * `ready` shows something instead of crashing the host. A missing NETWORK grant still throws,
 * because an ungranted host capability must fail loudly (ADR-0003).
 */
internal class AgentModelService(
    private val repository: ProviderRepository,
    private val network: NetworkGate,
    private val transport: ProviderTransport,
) : ModelService {

    override val models: List<ModelRef>
        get() = runCatching { ProviderDocuments.selectedFirst(repository.load()) }.getOrDefault(emptyList())

    override suspend fun complete(
        model: ModelRef,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
    ): AgentReply = withContext(Dispatchers.IO) {
        // A revoked grant must fail on the next call, not on the next activation (ADR-0003).
        network.ensure()
        val providers = try {
            repository.load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return@withContext errorReply(model, "读取模型提供商配置失败：${error.message ?: error::class.simpleName}")
        }
        val provider = providers.firstOrNull { it.id == model.provider }
            ?: return@withContext errorReply(model, "未找到模型提供商 ${model.provider}")
        if (!provider.enabled) {
            return@withContext errorReply(model, "模型提供商 ${provider.name} 已禁用")
        }
        ProviderDocuments.baseUrlError(provider.baseUrl)?.let { error ->
            return@withContext errorReply(model, "模型提供商 ${provider.name}：$error")
        }
        val descriptor = provider.models.firstOrNull { it.id == model.model }
            ?: return@withContext errorReply(model, "提供商 ${provider.name} 未配置模型 ${model.model}")
        val resolved = ModelRef(provider = provider.id, model = descriptor.id)
        val body = try {
            OpenAiProtocol.requestBody(descriptor.id, messages, tools, descriptor.parameters)
        } catch (error: Throwable) {
            return@withContext errorReply(resolved, "请求体构造失败：${error.message ?: error::class.simpleName}")
        }
        val response = try {
            transport.post(
                url = ProviderUrls.chatCompletions(provider.baseUrl),
                headers = ProviderDocuments.requestHeaders(provider),
                body = body,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return@withContext errorReply(resolved, "网络错误：${error.message ?: error::class.simpleName}")
        }
        if (response.code !in 200..299) {
            return@withContext errorReply(
                resolved,
                "HTTP ${response.code}：${response.body.take(MAX_ERROR_BODY)}",
            )
        }
        try {
            OpenAiProtocol.parseReply(response.body, resolved)
        } catch (failure: OpenAiFailure) {
            errorReply(resolved, failure.message ?: "模型接口返回错误")
        }
    }

    private fun errorReply(model: ModelRef, reason: String): AgentReply {
        val text = "模型调用失败：$reason"
        return AgentReply(
            message = AgentMessage(role = AgentRole.ASSISTANT, content = text),
            model = model,
            events = listOf(AgentEvent(kind = AgentEvent.Kind.ERROR, text = reason)),
        )
    }

    private companion object {
        const val MAX_ERROR_BODY = 300
    }
}
