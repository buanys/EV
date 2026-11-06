package ev.buan.plugins.agent.api

/**
 * One image attached to a message, already encoded for the wire.
 *
 * Base64 rather than a file path or content URI: a plugin's files live in storage the provider
 * process cannot open, and a `data:` URI is the one form every OpenAI-compatible server accepts.
 *
 * Images are transient by design. They travel with the request and are dropped when memory stores
 * the message - a transcript holding a 1 MB screenshot in every turn would eat the storage budget
 * within a day, and the model can always re-read the file.
 */
data class AgentImage(
    /** Wire mime type, e.g. `image/jpeg`. */
    val mimeType: String,
    /** Base64 body: no `data:` prefix, no line breaks. */
    val base64: String,
    /** Optional short name for logs, e.g. `screen-1729.jpg`. */
    val name: String? = null,
)
