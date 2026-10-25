package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelProvider

/**
 * Shared test doubles for the provider layer.
 *
 * The repository double mirrors [ProviderStore]'s semantics (store only what differs, merge the
 * catalog on read), so service-level tests exercise the same ordering and keyless rules as the
 * device without touching `PluginStorage`.
 */
internal class FakeProviderRepository(
    initial: List<ModelProvider> = emptyList(),
    private val catalog: List<ModelProvider> = ProviderCatalog.builtins,
) : ProviderRepository {
    var stored: List<ModelProvider> = initial
        private set

    var saveCount: Int = 0
        private set

    var loadFailure: Throwable? = null

    override fun load(): List<ModelProvider> {
        loadFailure?.let { throw it }
        return ProviderDocuments.merge(stored, catalog)
    }

    override fun save(providers: List<ModelProvider>) {
        stored = providers
        saveCount++
    }
}

internal data class RecordedHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

internal class RecordingTransport : ProviderTransport {
    val requests = mutableListOf<RecordedHttpRequest>()

    var response: ProviderResponse = ProviderResponse(code = 200, body = "{}")
    var failure: Throwable? = null

    override fun get(url: String, headers: Map<String, String>): ProviderResponse =
        record(RecordedHttpRequest("GET", url, headers, null))

    override fun post(url: String, headers: Map<String, String>, body: String): ProviderResponse =
        record(RecordedHttpRequest("POST", url, headers, body))

    private fun record(request: RecordedHttpRequest): ProviderResponse {
        requests += request
        failure?.let { throw it }
        return response
    }
}

internal class RecordingNetworkGate : NetworkGate {
    var checks: Int = 0
        private set

    override fun ensure() {
        checks++
    }
}
