package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModelProviderService] over [ProviderRepository] and [ProviderTransport].
 *
 * The service is a thin coordinator: pure document operations live in [ProviderDocuments], HTTP
 * interpretation in [ProviderProbes]. Storage methods are synchronous (a small local file, the UI
 * calls them from an IO coroutine), while the two network probes are `suspend`.
 *
 * [catalog] is injectable so tests can use a tiny directory; the plugin passes the real catalog.
 */
internal class ProviderServiceImpl(
    private val repository: ProviderRepository,
    private val network: NetworkGate,
    private val transport: ProviderTransport,
    private val catalog: List<ModelProvider> = ProviderCatalog.builtins,
) : ModelProviderService {

    override fun providers(): List<ModelProvider> = repository.load()

    override fun saveProvider(provider: ModelProvider) {
        persist(ProviderDocuments.upsert(repository.load(), provider, catalog))
    }

    override fun deleteProvider(id: String) {
        val current = repository.load()
        // A catalog id cannot really be deleted - the next read would resurrect the default - so it
        // is reset instead, which is also what the settings page's "reset" action asks for.
        val updated = if (catalog.any { it.id == id }) {
            ProviderDocuments.reset(current, id, catalog)
        } else {
            ProviderDocuments.delete(current, id)
        }
        persist(updated)
    }

    override fun resetProvider(id: String) {
        persist(ProviderDocuments.reset(repository.load(), id, catalog))
    }

    override suspend fun fetchRemoteModels(providerId: String): List<ModelDescriptor> =
        withContext(Dispatchers.IO) {
            network.ensure()
            val provider = requireProvider(providerId)
            ProviderDocuments.baseUrlError(provider.baseUrl)?.let { error ->
                throw ProviderRequestException("模型提供商 ${provider.name}：$error")
            }
            val response = getModels(provider)
            if (response.code !in 200..299) {
                throw ProviderRequestException(
                    "获取模型列表失败：${ProviderProbes.testResult(response).message}",
                )
            }
            ProviderProbes.parseRemoteModels(response.body)
                ?: throw ProviderRequestException("模型列表响应不是 OpenAI 兼容格式（缺少 data 数组）")
        }

    override suspend fun testConnection(providerId: String): ModelTestResult =
        withContext(Dispatchers.IO) {
            val provider = repository.load().firstOrNull { it.id == providerId }
                ?: return@withContext ModelTestResult(false, "未找到模型提供商 $providerId")
            ProviderDocuments.baseUrlError(provider.baseUrl)?.let { error ->
                return@withContext ModelTestResult(false, "连接失败：$error")
            }
            try {
                network.ensure()
                ProviderProbes.testResult(getModels(provider))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                ModelTestResult(false, "连接失败：${error.message ?: error::class.simpleName}")
            }
        }

    private fun getModels(provider: ModelProvider): ProviderResponse =
        transport.get(ProviderUrls.models(provider.baseUrl), ProviderDocuments.requestHeaders(provider))

    private fun requireProvider(id: String): ModelProvider =
        repository.load().firstOrNull { it.id == id }
            ?: throw ProviderRequestException("未找到模型提供商 $id")

    private fun persist(providers: List<ModelProvider>) {
        repository.save(ProviderDocuments.storable(providers, catalog))
    }
}
