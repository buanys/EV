package ev.buan.plugins.agent.model

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One HTTP result from a provider endpoint; status and body are all the model layer needs. */
internal data class ProviderResponse(
    val code: Int,
    val body: String,
)

/**
 * The HTTP seam of the model layer.
 *
 * The concrete implementation is OkHttp (a host-shared package); unit tests substitute a fake so
 * request URLs/headers can be asserted and responses scripted without a network. Methods block, so
 * callers run them on `Dispatchers.IO`.
 */
internal interface ProviderTransport {
    fun get(url: String, headers: Map<String, String>): ProviderResponse

    fun post(url: String, headers: Map<String, String>, body: String): ProviderResponse
}

/** Thin OkHttp adapter: the client itself belongs to the host, only the request is built here. */
internal class OkHttpProviderTransport(
    private val client: OkHttpClient = defaultClient(),
) : ProviderTransport {

    override fun get(url: String, headers: Map<String, String>): ProviderResponse =
        execute(Request.Builder().url(url).apply { headers.forEach(::header) }.get().build())

    override fun post(url: String, headers: Map<String, String>, body: String): ProviderResponse =
        execute(
            Request.Builder()
                .url(url)
                .apply { headers.forEach(::header) }
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )

    private fun execute(request: Request): ProviderResponse =
        client.newCall(request).execute().use { response ->
            ProviderResponse(code = response.code, body = response.body.string())
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Generous read timeout: a long completion legitimately takes minutes to stream back. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * The host NETWORK permission check, as a seam.
 *
 * Keeping it a one-method interface keeps the model layer free of `plugin-api` types and lets the
 * unit tests run without a host: the plugin wires it to `PermissionService.require(NETWORK)`.
 */
internal fun interface NetworkGate {
    fun ensure()
}

/** Thrown by [ModelProviderService]-style operations with a message meant for the settings page. */
internal class ProviderRequestException(message: String) : IOException(message)

/** Endpoint URLs derived from a provider's base URL; the base keeps its version segment. */
internal object ProviderUrls {
    fun models(baseUrl: String): String = base(baseUrl) + "/models"

    fun chatCompletions(baseUrl: String): String = base(baseUrl) + "/chat/completions"

    private fun base(raw: String): String = raw.trim().trimEnd('/')
}
