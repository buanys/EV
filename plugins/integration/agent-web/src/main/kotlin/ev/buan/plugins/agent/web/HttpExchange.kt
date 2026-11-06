package ev.buan.plugins.agent.web

import java.io.EOFException
import java.io.InputStream

/** One parsed HTTP/1.1 request. Kept free of Android and org.json so unit tests run on a JVM. */
internal data class HttpRequest(
    val method: String,
    /** Path without the query string, always starting with `/`. */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal data class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
) {
    fun bytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.encodeToByteArray()
        return head + body
    }

    companion object {
        fun json(status: Int, text: String) = HttpResponse(status, "application/json; charset=utf-8", text.encodeToByteArray())
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.encodeToByteArray())

        private fun statusText(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            501 -> "Not Implemented"
            else -> "Internal Server Error"
        }
    }
}

internal object HttpExchange {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 256 * 1024

    /** Reads one request; null on EOF before anything arrived (a closed keep-alive probe). */
    fun readRequest(input: InputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val headText = head.decodeToString()
        val lines = headText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: throw badRequest("空请求行")
        if (requestLine.size < 2) throw badRequest("畸形请求行")
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw badRequest("畸形头部：$line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) throw badRequest("请求体过大")
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) throw EOFException("请求体被截断")
            read += n
        }
        val qIndex = target.indexOf('?')
        val rawPath = if (qIndex >= 0) target.substring(0, qIndex) else target
        val query = if (qIndex >= 0) parseQuery(target.substring(qIndex + 1)) else emptyMap()
        val path = confinePath(rawPath)
        return HttpRequest(method, path, query, headers, body)
    }

    fun parseQuery(text: String): Map<String, String> =
        text.split('&').filter { it.isNotBlank() }.associate { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) urlDecode(pair) to ""
            else urlDecode(pair.substring(0, eq)) to urlDecode(pair.substring(eq + 1))
        }

    /**
     * Maps a URL path onto a storage-relative path. The result never escapes its root:
     * dot segments collapse lexically and anything trying to climb out lands back at root.
     */
    fun confinePath(rawPath: String): String {
        val segments = rawPath.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (segment in segments) {
            if (segment == "..") {
                if (stack.isNotEmpty()) stack.removeLast()
            } else {
                stack.addLast(segment)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun readHead(input: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buffer.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                throw EOFException("头部被截断")
            }
            buffer.write(b)
            matched = if (b == end[matched].toInt()) matched + 1 else if (b == end[0].toInt()) 1 else 0
            if (matched == 4) return buffer.toByteArray().copyOf(buffer.size() - 4)
        }
        throw badRequest("头部过大")
    }

    private fun urlDecode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

    private fun badRequest(message: String) = HttpRequestException(400, message)
}

internal class HttpRequestException(val status: Int, message: String) : Exception(message)
