package ev.buan.plugins.agent.browse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 平台侧策略：WebView 被**允许**做什么，以及网页的"名词"怎么翻成 Android 的说法。
 *
 * 这里没有 Compose 状态，也没有"谁在什么时机回调"的判断——那些在 [BrowserClients]（回调）
 * 和 [BrowserWebView]（状态挂哪儿）。这个文件只回答一个问题：一个网页能碰到设备的哪一部分。
 *
 * 所以下面每一行都是安全边界，不是默认值顺手写的：
 *
 * | 开关 | 值 | 反过来的后果 |
 * |---|---|---|
 * | `javaScriptEnabled` | 开 | 普通站点直接白页 |
 * | `domStorageEnabled` | 开 | 登录态、购物车全丢 |
 * | `allowFileAccess` / `allowContentAccess` | **关** | 页面能用 file:// 读这个应用的私有目录 |
 * | `mixedContentMode` | **NEVER_ALLOW** | 运营商可以往加密页面里塞东西 |
 * | `setSupportMultipleWindows` | 开 | `target=_blank` 根本不进 `onCreateWindow` |
 * | `javaScriptCanOpenWindowsAutomatically` | 关 | 广告可以自己弹窗 |
 * | `IN_PANEL_SCHEMES` | 见下 | `tel:` `intent:` 被自己吃掉，或者 http 被丢给系统浏览器 |
 */

/**
 * 面板里每个 WebView 的统一配置，只在 `AndroidView` 的 factory 里调一次。
 *
 * 抽成函数不是为了复用（只有一个调用点），而是为了让"这个面板允许网页做什么"能一次读完：
 * 以后调安全设置不用在一个 Composable 里翻。
 */
internal fun configureWebView(view: WebView) {
    val settings = view.settings
    // JS 与 DOM storage 是普通网站能用的前提。文件访问保持关闭：
    // 开着它，页面就能用 file:// 读到这个应用的私有目录。
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    // 混合内容按最严格处理：https 页面里的 http 资源会被直接拦掉。
    // 放行一次等于让运营商可以往加密页面里塞东西。
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // 手机版的网页需要这两个，否则桌面版页面会缩成一条。
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    // 双指缩放：开着，但不要那个又老又丑的 +/- 悬浮按钮。
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // 没有它，target=_blank 的链接根本不会进 onCreateWindow。
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = false
    // 透明，让面板自己的背景在第一帧页面到达前可见。
    view.setBackgroundColor(Color.TRANSPARENT)
}

/**
 * 不经过 client 的两个监听器：下载与长按链接。
 *
 * 网页点"下载"时没有任何回调会告诉你，`setDownloadListener` 是唯一入口，不装就是无声无息；
 * 长按不给菜单则落在文字选择上，而"我想把这个链接发出去"是这个面板里最常发生的事之一。
 *
 * 下载用的是这个 WebView 自己的 context（它由面板的 context 创建，和面板同源），
 * 只借来取 DOWNLOAD_SERVICE。
 */
internal fun attachWebViewListeners(view: WebView, session: BrowserSession) {
    view.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
        enqueueDownload(view.context, url, contentDisposition, mimeType)
    }

    view.setOnLongClickListener { target ->
        // 这个回调的入参是 View，不是 WebView；不转一下就拿不到 hitTestResult。
        val web = target as? WebView ?: return@setOnLongClickListener false
        val hit = web.hitTestResult
        val url = hit.extra
        if (url.isNullOrBlank()) return@setOnLongClickListener false
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                showLinkMenu(web.context, url, session.pageTitle)
                true
            }

            else -> false
        }
    }
}

/** 面板自己处理、不交给系统的 scheme。 */
internal val IN_PANEL_SCHEMES = setOf(
    "http", "https", "about", "data", "file", "content", "javascript", "blob",
)

/**
 * `tel:` `mailto:` `market:` `intent:` 交给系统。
 *
 * `intent://` 要单独解析：`ACTION_VIEW` 不认识它。解析出来的 Intent 必须清掉
 * component/selector——否则一个网页可以指定要启动哪个 Activity 的哪个组件。
 */
internal fun openExternally(context: Context, uri: Uri): Boolean {
    if (uri.scheme.equals("intent", ignoreCase = true)) {
        val parsed = runCatching {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return false
        parsed.component = null
        parsed.selector = null
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(parsed) }.isSuccess
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** 网页资源名 → Android 运行时权限；不相干的（如 DRM）返回 null，表示不需要系统权限。 */
internal fun androidPermissionFor(resource: String): String? = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

/** 证书错误说人话。只解释"为什么不让你进"，不给"继续"这个选项。 */
internal fun sslMessage(primaryError: Int): String = when (primaryError) {
    android.net.http.SslError.SSL_DATE_INVALID -> "这个网站的证书已经过期，连接不安全。"
    android.net.http.SslError.SSL_EXPIRED -> "这个网站的证书过期了，连接不安全。"
    android.net.http.SslError.SSL_IDMISMATCH -> "证书上的域名和这个网站的域名对不上，可能是伪装站点。"
    android.net.http.SslError.SSL_NOTYETVALID -> "这个网站的证书还没生效。"
    android.net.http.SslError.SSL_UNTRUSTED -> "签发这个网站证书的机构不被系统信任。"
    else -> "这个网站的证书有问题，连接不安全。"
}
