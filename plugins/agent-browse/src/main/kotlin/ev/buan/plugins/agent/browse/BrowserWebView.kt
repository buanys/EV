package ev.buan.plugins.agent.browse

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 平台 WebView 的宿主：创建、配置、回调转发、销毁。
 *
 * 页面级的策略集中在这里，UI 那边不用知道。四个"正常人一定会撞到"的回调也在这里：
 * 新窗口、加载失败、JS 对话框、下载——旧版一个都没有，于是链接点了没反应、断网白屏、
 * 页面调 alert 卡住、点下载无声无息。
 */
@Composable
internal fun BrowserWebView(
    session: BrowserSession,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // JS 与 DOM storage 是普通网站能用的前提；文件访问保持关闭，
                // 否则页面可以通过 file:// 读到这个应用的私有数据。
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 没有它，target=_blank 的链接根本不会进 onCreateWindow。
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = false
                // 透明，让面板自己的背景在第一帧页面到达前可见。
                setBackgroundColor(Color.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    // 永远不把点击交给外部浏览器：这个面板就是浏览器。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = false

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        session.onStarted(url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        session.onFinished(url, view.title)
                    }

                    /**
                     * 只处理主框架。子资源（图片、广告、统计）失败不该把整页判死——
                     * 那会让一个本来能读的页面看起来彻底坏了。
                     */
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (!request.isForMainFrame) return
                        val description = error.description?.toString().orEmpty()
                        session.onFailed(
                            description.ifBlank { "无法打开这个页面（错误码 ${error.errorCode}）" },
                        )
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        session.onTitle(title)
                    }

                    /**
                     * 新窗口：单标签面板把它接管到当前 WebView。
                     *
                     * 系统会先给一个空 WebView 让我们把目标地址转走；不实现这个回调时，
                     * `target=_blank` 的链接点了完全没反应——这是旧版最容易被撞到的缺陷。
                     */
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val host = view ?: return false
                        val probe = WebView(host.context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    session.navigate(request.url.toString())
                                    // 不能在回调里直接 destroy：这一帧还在用它。
                                    post { destroy() }
                                    return true
                                }
                            }
                        }
                        transport.webView = probe
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onJsAlert(
                        view: WebView,
                        url: String,
                        message: String,
                        result: JsResult,
                    ): Boolean {
                        showDialog(
                            context = view.context,
                            message = message,
                            positiveLabel = "确定",
                            onPositive = { result.confirm() },
                            onDismiss = { result.cancel() },
                        )
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView,
                        url: String,
                        message: String,
                        result: JsResult,
                    ): Boolean {
                        showDialog(
                            context = view.context,
                            message = message,
                            positiveLabel = "确定",
                            negativeLabel = "取消",
                            onPositive = { result.confirm() },
                            onDismiss = { result.cancel() },
                        )
                        return true
                    }
                }

                setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                    enqueueDownload(context, url, contentDisposition, mimeType)
                }

                session.attach(this)
            }
        },
    )

    // 面板关闭时销毁 WebView：先摘掉两个 client（它们捕获了页面状态），
    // 再从父容器移除，最后 destroy——挂在父容器上的 WebView 会泄漏。
    DisposableEffect(Unit) {
        onDispose {
            session.view?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.webChromeClient = WebChromeClient()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            session.detach()
        }
    }
}

/**
 * 网页里的 alert / confirm。
 *
 * 不实现这些回调时 WebView 会把请求一直挂着：页面以为对话框还没被回答，
 * 之后的脚本都不执行——用户看到的是"这个站点了没反应"。
 */
private fun showDialog(
    context: Context,
    message: String,
    positiveLabel: String,
    negativeLabel: String? = null,
    onPositive: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog.Builder(context)
        .setMessage(message)
        .setPositiveButton(positiveLabel) { _, _ -> onPositive() }
        .apply {
            if (negativeLabel != null) {
                setNegativeButton(negativeLabel) { _, _ -> onDismiss() }
            } else {
                setOnCancelListener { onDismiss() }
            }
        }
        .show()
}

/**
 * 下载：交给系统的 DownloadManager。
 *
 * 用 DownloadManager 而不是自己读流，是因为它负责断点、通知和"下载完打开"，
 * 而这些用一次 WebView 会话的 App 自己重写一遍没有意义。
 * 拿不到文件名时退回到 URL 的最后一段。
 */
private fun enqueueDownload(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
) {
    val request = runCatching {
        DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileNameFor(url, contentDisposition),
            )
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        }
    }.getOrNull() ?: return
    runCatching {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}

/** `attachment; filename="x.pdf"` → `x.pdf`；否则取 URL 最后一段；都没有就用 download。 */
private fun fileNameFor(url: String, contentDisposition: String?): String {
    val fromHeader = contentDisposition
        ?.substringAfter("filename=", "")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotBlank() }
    if (fromHeader != null) return fromHeader

    val fromUrl = url.substringAfterLast('/')
        .substringBefore('?')
        .takeIf { it.isNotBlank() && !it.contains(':') }
    return fromUrl ?: "download"
}
