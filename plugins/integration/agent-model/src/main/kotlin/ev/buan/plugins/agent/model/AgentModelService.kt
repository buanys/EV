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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val target = when (val resolved = targetOf(model)) {
            is Target.Failed -> return@withContext resolved.reply
            is Target.Ready -> resolved
        }
        val body = try {
            OpenAiProtocol.requestBody(target.descriptor.id, messages, tools, target.descriptor.parameters)
        } catch (error: Throwable) {
            return@withContext errorReply(target.ref, "请求体构造失败：${error.message ?: error::class.simpleName}")
        }
        val response = try {
            transport.post(
                url = ProviderUrls.chatCompletions(target.provider.baseUrl),
                headers = ProviderDocuments.requestHeaders(target.provider),
                body = body,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return@withContext errorReply(target.ref, "网络错误：${error.message ?: error::class.simpleName}")
        }
        if (response.code !in 200..299) {
            return@withContext errorReply(
                target.ref,
                "HTTP ${response.code}：${response.body.take(MAX_ERROR_BODY)}",
            )
        }
        try {
            OpenAiProtocol.parseReply(response.body, target.ref)
        } catch (failure: OpenAiFailure) {
            errorReply(target.ref, failure.message ?: "模型接口返回错误")
        }
    }

    /**
     * Streaming sibling of [complete].
     *
     * Same provider resolution and the same failure replies; the difference is that text reaches
     * [onDelta] while the server is still producing it. Deltas are pumped through a channel rather
     * than called from the transport callback: the callback runs on the OkHttp thread, and a
     * `FlowCollector.emit` must happen in the collector's coroutine.
     *
     * The finished [AgentReply] is still returned whole - the tool protocol needs ids and complete
     * argument JSON before the next round can start.
     */
    override suspend fun completeStreaming(
        model: ModelRef,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        onDelta: suspend (String) -> Unit,
    ): AgentReply = withContext(Dispatchers.IO) {
        val target = when (val resolved = targetOf(model)) {
            is Target.Failed -> return@withContext resolved.reply
            is Target.Ready -> resolved
        }
        val body = try {
            OpenAiProtocol.streamRequestBody(target.descriptor.id, messages, tools, target.descriptor.parameters)
        } catch (error: Throwable) {
            return@withContext errorReply(target.ref, "请求体构造失败：${error.message ?: error::class.simpleName}")
        }
        val stream = OpenAiStream(target.ref)
        coroutineScope {
            val deltas = Channel<String>(Channel.UNLIMITED)
            val pump = launch { for (delta in deltas) onDelta(delta) }
            val response = try {
                transport.postSse(
                    url = ProviderUrls.chatCompletions(target.provider.baseUrl),
                    headers = ProviderDocuments.requestHeaders(target.provider),
                    body = body,
                ) { payload -> stream.feed(payload)?.let(deltas::trySend) }
            } catch (cancel: CancellationException) {
                deltas.close()
                pump.cancel()
                throw cancel
            } catch (failure: OpenAiFailure) {
                deltas.close()
                pump.join()
                return@coroutineScope errorReply(target.ref, failure.message ?: "模型接口返回错误")
            } catch (error: Throwable) {
                deltas.close()
                pump.join()
                return@coroutineScope errorReply(target.ref, "网络错误：${error.message ?: error::class.simpleName}")
            }
            deltas.close()
            pump.join()
            if (response.code !in 200..299) {
                errorReply(target.ref, "HTTP ${response.code}：${response.body.take(MAX_ERROR_BODY)}")
            } else {
                stream.reply()
            }
        }
    }

    private sealed interface Target {
        data class Ready(
            val provider: ev.buan.plugins.agent.api.ModelProvider,
            val descriptor: ev.buan.plugins.agent.api.ModelDescriptor,
            val ref: ModelRef,
        ) : Target

        data class Failed(val reply: AgentReply) : Target
    }

    /** Provider lookup shared by both call paths, so their failure text cannot drift apart. */
    private suspend fun targetOf(model: ModelRef): Target {
        network.ensure()
        val providers = try {
            repository.load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return Target.Failed(
                errorReply(model, "读取模型提供商配置失败：${error.message ?: error::class.simpleName}"),
            )
        }
        val provider = providers.firstOrNull { it.id == model.provider }
            ?: return Target.Failed(errorReply(model, "未找到模型提供商 ${model.provider}"))
        if (!provider.enabled) {
            return Target.Failed(errorReply(model, "模型提供商 ${provider.name} 已禁用"))
        }
        ProviderDocuments.baseUrlError(provider.baseUrl)?.let { error ->
            return Target.Failed(errorReply(model, "模型提供商 ${provider.name}：$error"))
        }
        val descriptor = provider.models.firstOrNull { it.id == model.model }
            ?: return Target.Failed(errorReply(model, "提供商 ${provider.name} 未配置模型 ${model.model}"))
        return Target.Ready(
            provider = provider,
            descriptor = descriptor,
            ref = ModelRef(provider = provider.id, model = descriptor.id),
        )
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
