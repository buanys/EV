package ev.buan.plugins.agent.browse

/**
 * The address a user typed, as a WebView URL: a bare host gets `https://`, anything that already
 * names its scheme is left alone.
 *
 * "localhost:8080" is the reason this is a whitelist instead of "does it look like a scheme": that
 * string is a host and a port, not a `localhost:` scheme, so it must be prefixed too.
 */
internal fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
