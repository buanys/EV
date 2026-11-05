package ev.buan.plugins.agent.browse

import java.net.URLEncoder

/**
 * 地址栏里那串字，最后该请求哪个 URL。
 *
 * 旧版只有一条规则：不是 http/https/about 就加 `https://` 前缀。于是输入「今天天气」会变成
 * `https://今天天气`，等一会儿得到一张错误页——而人对着地址栏说话的意思显然是搜索。
 *
 * 判定顺序是「先当地址，不像地址才当搜索」，这是手机浏览器共同的模型：
 *
 * - 带了 scheme（`http://` `about:` `javascript:` …）→ 原样，用户已经说清楚了；
 * - 含有空白 → 搜索。地址里不可能有空格，而 `hello world` 只可能是一句话；
 * - 主机部分有点（`example.com`、`192.168.1.1`）或是 `localhost` → 补 `https://`；
 * - 其余（`zhihu`、`今天天气`、`kotlin 协程`）→ 搜索。
 *
 * 搜索模板跟 `web_search` 的读屏引擎第一顺位一致，都是 Bing：同一个面板里两个入口却给出
 * 不同结果源，用户会以为其中一个坏了。
 */
internal fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("about:") ||
        lower.startsWith("data:") ||
        lower.startsWith("javascript:") ||
        lower.startsWith("file:") ||
        lower.startsWith("content:")
    ) {
        return trimmed
    }

    // 有空白就是一句话。放在最前面，避免「a b.com」这种混合输入被当成主机。
    if (trimmed.any { it.isWhitespace() }) return searchUrl(trimmed)

    val host = trimmed
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')      // user:pass@host
        .substringBefore(':')     // host:port

    return when {
        host.equals("localhost", ignoreCase = true) -> "https://$trimmed"
        host.count { it == '.' } in 1..3 -> "https://$trimmed"
        else -> searchUrl(trimmed)
    }
}

/**
 * 搜索词 → 结果页。
 *
 * `URLEncoder` 会把空格编成 `+`，Bing 与百度都接受；不编码的话中文在 `loadUrl` 里会被
 * 拒掉（WebView 要求 URL 是合法的）。
 */
internal fun searchUrl(query: String): String =
    SEARCH_TEMPLATE + URLEncoder.encode(query, "UTF-8")

private const val SEARCH_TEMPLATE = "https://cn.bing.com/search?q="
