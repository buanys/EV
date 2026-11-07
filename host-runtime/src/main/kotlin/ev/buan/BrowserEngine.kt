package ev.buan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 一个窗口的 WebView 与它的两块回调板：`WebViewClient`（导航、加载结果、证书）和
 * `WebChromeClient`（标题、进度、新窗口、JS 对话框、文件、权限、全屏）。
 *
 * 这一层从插件搬进宿主的理由很直接：**WebView 已经在这里了**，回调只能装在它身上。
 * 插件曾经自己造 WebView、自己装回调，于是同一台机器有两套会话——agent 打开的页面
 * 用户看不见，用户打开的页面 agent 摸不着。
 *
 * 这张表是这个文件存在的理由——少一个回调的表现都是"这个站点了没反应"，而不是报错：
 *
 * | 能力 | 少了它 | 回调 |
 * |---|---|---|
 * | 新窗口 | `target=_blank` 点了没反应 | `onCreateWindow` |
 * | 加载失败 | 断网白屏 | `onReceivedError` |
 * | JS 对话框 | `alert` 挂住后续脚本 | `onJsAlert` / `onJsConfirm` / `onJsPrompt` |
 * | 文件上传 | 选择器不弹 | `onShowFileChooser` |
 * | 摄像头/麦克风 | `getUserMedia` 永远 pending | `onPermissionRequest` |
 * | 全屏视频 | 只能在小窗口里看 | `onShowCustomView` |
 * | 证书错误 | 白屏，且默认行为不可见 | `onReceivedSslError` |
 *
 * 三件事（选文件、运行时权限、全屏）走 [org.cordis.api.BrowserUi]，因为它们只有界面做得到；
 * 界面没接管时按"用户取消"结掉，绝不让页面等一个永远不来的回答。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun HostBrowserSession.createWebView(tab: BrowserTab): WebView {
    val web = WebView(appContext)
    configureBrowserWebView(web)
    web.webViewClient = webClientFor(tab)
    web.webChromeClient = chromeClientFor(tab)
    web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
        enqueueDownload(appContext, url, contentDisposition, mimeType)
    }
    web.setOnLongClickListener { target ->
        // 这个回调的入参是 View，不是 WebView；不转一下就拿不到 hitTestResult。
        val hit = (target as? WebView)?.hitTestResult ?: return@setOnLongClickListener false
        val link = hit.extra
        if (link.isNullOrBlank() || hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            return@setOnLongClickListener false
        }
        showLinkMenu(appContext, link, tab.title)
        true
    }
    return web
}

/** 页面导航、加载结果、证书。全部落到会话的状态或 [BrowserPolicy] 的策略里。 */
private fun HostBrowserSession.webClientFor(tab: BrowserTab): WebViewClient =
    object : WebViewClient() {

        /**
         * http(s) 一律留在引擎里——这就是浏览器。
         *
         * 其余 scheme（`tel:` `mailto:` `market:` `intent:`）引擎处理不了，交给系统。
         * `isRedirect` 必须放行：OAuth 回跳、短链都是重定向，在这里吃掉它们，
         * 登录流程会断在"回到你的应用"那一步。
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            if (scheme in IN_PANEL_SCHEMES) return false
            if (!request.isForMainFrame || request.isRedirect) return false
            return openExternally(appContext, request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onTabStarted(tab, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            onTabFinished(tab, url, view.title)
        }

        /**
         * 前进/后退能力在这里就变了，比 onPageFinished 早。
         * 只在 onPageFinished 同步，返回按钮会晚一拍才亮起来。
         */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onTabHistoryChanged(tab)
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
            onTabFailed(tab, description.ifBlank { "无法打开这个页面（错误码 ${error.errorCode}）" })
        }

        /**
         * 证书不被信任：取消，并且说清楚为什么。
         *
         * 永远不 `handler.proceed()`。放行一次的意思是「把这一页的密码和 cookie 交给中间人」，
         * 而这个按钮一旦存在，人就会点。
         */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onTabFailed(tab, sslMessage(error.primaryError))
        }
    }

/** 标题、进度、新窗口、JS 对话框、文件、权限、全屏。 */
private fun HostBrowserSession.chromeClientFor(tab: BrowserTab): WebChromeClient =
    object : WebChromeClient() {

        override fun onReceivedTitle(view: WebView?, title: String?) {
            onTabTitle(tab, title)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onTabProgress(tab, newProgress)
        }

        /**
         * 新窗口：`target=_blank` / `window.open`。**开成一个真正的窗口**，不是把当前页顶掉——
         * 顶掉之后用户按返回回来会发现刚才那一页没了。
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val host = view ?: return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            return onCreateWindowRequest(tab, host, transport, resultMsg)
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean {
            showWebDialog(
                context = dialogContext(),
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
            showWebDialog(
                context = dialogContext(),
                message = message,
                positiveLabel = "确定",
                negativeLabel = "取消",
                onPositive = { result.confirm() },
                onDismiss = { result.cancel() },
            )
            return true
        }

        /**
         * `prompt()` 和 alert/confirm 是同一类：不回答，页面之后的脚本全停。
         * 比它们更容易被忽略，因为会用到 prompt 的页面看起来更"高级"。
         */
        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsPromptResult,
        ): Boolean {
            showWebPrompt(
                context = dialogContext(),
                message = message,
                defaultValue = defaultValue,
                onResult = { input -> if (input == null) result.cancel() else result.confirm(input) },
            )
            return true
        }

        /**
         * 上传按钮：网页点"选择文件"要走这里。
         *
         * 界面没接管时按"用户取消"结掉——返回 false 而不用 null 结掉的话，
         * 这个页面以后再也弹不出选择器。
         */
        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = requestFileFromWeb(filePathCallback, fileChooserParams)

        /**
         * `getUserMedia`（扫码、录音、视频通话）。
         *
         * 两件事都要过：网页这一层的 grant，和 Android 运行时权限。
         * 只做前者，页面的摄像头会打开失败；只做后者，请求一直挂着。
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = request.resources.mapNotNull(::androidPermissionFor).distinct()
            val missing = needed.filter { permission ->
                appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                runCatching { request.grant(request.resources) }
            } else if (!requestPermissionsFromWeb(request, missing.toTypedArray())) {
                runCatching { request.deny() }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            browserUi?.showFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            browserUi?.hideFullscreen()
        }
    }

/**
 * 对话框用哪个 context：能用 Activity 就用 Activity。
 *
 * 用 Application 建 `AlertDialog` 在多数 ROM 上会抛 `BadTokenException`（应用级 context
 * 没有窗口令牌），而 [HostBrowserSession] 里只有应用级 context——所以这里显式问一次。
 */
internal fun HostBrowserSession.dialogContext(): Context = hostActivity() ?: appContext
