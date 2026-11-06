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

    /**
     * POSTs a `stream = true` request and hands each SSE payload to [onData] as it arrives.
     *
     * The callback runs on the calling thread, once per `data:` line, before the call returns —
     * that is the whole point: the caller forwards each piece to the UI. `[DONE]`, blank payloads
     * and `:` comments are filtered out here so callers only ever see JSON objects.
     *
     * A non-2xx answer never streams: the whole error body is read and returned, so the caller can
     * report `HTTP 401: {...}` the same way the non-streaming path does.
     */
    fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        onData: (String) -> Unit,
    ): ProviderResponse
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

    override fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        onData: (String) -> Unit,
    ): ProviderResponse = execute(
        Request.Builder()
            .url(url)
            .apply { headers.forEach(::header) }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .build(),
        onData = onData,
    )

    private fun execute(
        request: Request,
        onData: ((String) -> Unit)? = null,
    ): ProviderResponse = client.newCall(request).execute().use { response ->
        if (onData == null || !response.isSuccessful) {
            return ProviderResponse(code = response.code, body = response.body.string())
        }
        // Read line by line instead of buffering the body: a streamed completion can take minutes,
        // and the caller needs each piece while it is still being produced.
        val source = response.body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            onData(payload)
        }
        ProviderResponse(code = response.code, body = "")
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
