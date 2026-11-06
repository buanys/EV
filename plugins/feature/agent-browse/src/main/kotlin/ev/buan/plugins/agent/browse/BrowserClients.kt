package ev.buan.plugins.agent.browse

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
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
 * 网页的两种回调板：`WebViewClient`（导航、加载结果、证书）与 `WebChromeClient`
 * （标题、进度、新窗口、JS 对话框、文件、权限、全屏）。
 *
 * 它们被搬出 Composable，只带走一个 [BrowserCallbacks]：需要 Compose 状态的三件事
 * （文件选择器、运行时权限、全屏覆盖层）从这里"喊一声"，其余全部直接落到 [BrowserSession]。
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
 * 另外两件"看不见但一直在错"的事：进度条用的是假动画（真值在 `onProgressChanged`），
 * 返回按钮的可用状态只在 `onPageFinished` 同步（真正的时机是 `doUpdateVisitedHistory`）。
 */

/**
 * 回调板里"只有 Compose 侧才做得到"的那几件事。
 *
 * 故意只留 4 个方法：能当参数传的都当参数传了（`context`、`session`、
 * `FileChooserParams`、`PermissionRequest`），JS 对话框与长按菜单根本不需要 Compose 状态，
 * 所以它们不在这里，直接调 [showDialog] / [showPrompt] / [showLinkMenu]。
 *
 * 每个方法的实现都必须是"原来写在 Composable 里那段代码"的原样搬运，尤其是取消语义：
 * 网页在等一个回答，漏回一次，等着的那段脚本就永远不往下走。
 */
internal interface BrowserCallbacks {

    /**
     * 网页点了 `<input type=file>`；由 `onShowFileChooser` 同步调用。
     *
     * 实现方要：先把上一次没结掉的回调以 `null` 结掉（WebView 同一时刻只允许一个文件选择
     * 请求，漏回一次这个页面以后再也弹不出选择器）→ 存下这次的回调 → 拉起选择器 →
     * 选择器回来后用 `FileChooserParams.parseResult` 回填（取消时它给 `null`，正是 WebView
     * 要求的"用户没选"）。
     *
     * @return 是否真的把选择器拉起来了。`true` 表示"我接了，稍后给结果"；`false` 表示界面
     *   起不来——此时必须**已经**用 `onReceiveValue(null)` 结过这次请求，否则 WebView 会
     *   一直等一个永远不会来的结果。
     */
    fun requestFile(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean

    /**
     * 网页的 `getUserMedia` 要摄像头/麦克风，而 Android 运行时权限还没给；由
     * `onPermissionRequest` 调用——权限已经给全时不会进来（那一支调用方自己 `grant`）。
     *
     * 实现方要存下 [request] 并拉起系统权限对话框；结果回来后按 [androidPermissions] 的
     * 授予情况 `grant`/`deny`。**必须回答**，否则页面的 `getUserMedia` 永远 pending，
     * 用户看到的是"点了扫码没反应"。
     */
    fun requestPermissions(request: PermissionRequest, androidPermissions: Array<String>)

    /**
     * 页面要全屏（视频）；由 `onShowCustomView` 调用。
     *
     * 覆盖层由 Composable 渲染，所以状态也归它。实现方必须先摘掉上一块全屏视图再存新的：
     * 同一时刻只允许一个自定义视图，否则两个 view 抢父容器。不实现时全屏请求被忽略，
     * 视频只能在小窗口里看——不会崩。
     */
    fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)

    /**
     * 页面**自己**结束全屏（视频播完、页面内退出）；由 `onHideCustomView` 调用。
     *
     * 只清状态，**不要**回调 `callback.onCustomViewHidden()`：这一支本来就是 WebView 在
     * 通知我们"我退出了"，再回调回去会互相重入。（用户按返回键退出全屏走的是另一条路，
     * 那条才需要先告诉 WebView 一声。）
     */
    fun hideFullscreen()
}

/** 页面导航、加载结果、证书。全部落到 [BrowserSession] 或 [BrowserPlatform] 的策略里。 */
internal fun browserWebViewClient(session: BrowserSession): WebViewClient = object : WebViewClient() {
    /**
     * http(s) 一律留在面板里——这个面板就是浏览器。
     *
     * 其余 scheme（`tel:` `mailto:` `market:` `intent:`）面板处理不了，
     * 交给系统。`isRedirect` 必须放行：OAuth 回跳、短链都是重定向，
     * 在这里吃掉它们，登录流程会断在"回到你的应用"那一步。
     */
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val scheme = request.url.scheme?.lowercase() ?: return false
        if (scheme in IN_PANEL_SCHEMES) return false
        if (!request.isForMainFrame || request.isRedirect) return false
        return openExternally(view.context, request.url)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        session.onStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        session.onFinished(url, view.title)
    }

    /**
     * 前进/后退能力在这里就变了，比 onPageFinished 早。
     * 只在 onPageFinished 同步，返回按钮会晚一拍才亮起来。
     */
    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String?,
        isReload: Boolean,
    ) {
        session.syncControls()
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

    /**
     * 证书不被信任：取消，并且说清楚为什么。
     *
     * 永远不 `handler.proceed()`。放行一次的意思是「把这一页的密码和
     * cookie 交给中间人」，而这个按钮一旦存在，人就会点。系统默认实现
     * 是静默取消，用户看到的就是白屏——所以这里要留一句话。
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: android.net.http.SslError,
    ) {
        handler.cancel()
        session.onFailed(sslMessage(error.primaryError))
    }
}

/**
 * 标题、进度、新窗口、JS 对话框、文件、权限、全屏。
 *
 * [context] 只用来查运行时权限（`checkSelfPermission`）。必须是面板自己的 context，
 * 而不是 `view.context`——两者今天同源，但权限查询的语义属于"面板所在的进程/用户"。
 */
internal fun browserChromeClient(
    context: Context,
    session: BrowserSession,
    callbacks: BrowserCallbacks,
): WebChromeClient = object : WebChromeClient() {
    override fun onReceivedTitle(view: WebView?, title: String?) {
        session.onTitle(title)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        session.onProgress(newProgress)
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
        showPrompt(
            context = view.context,
            message = message,
            defaultValue = defaultValue,
            onResult = { input ->
                if (input == null) result.cancel() else result.confirm(input)
            },
        )
        return true
    }

    /**
     * 上传按钮：网页点"选择文件"要走这里。
     *
     * 上一次没回的 callback 必须先结掉——WebView 同一时刻只允许一个
     * 文件选择请求，漏回一次，这个页面以后再也弹不出选择器。
     * 那一步在 [BrowserCallbacks.requestFile] 里，这里只把结果交出去。
     */
    override fun onShowFileChooser(
        view: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        return callbacks.requestFile(filePathCallback, fileChooserParams)
    }

    /**
     * `getUserMedia`（扫码、录音、视频通话）。
     *
     * 两件事都要过：网页这一层的 grant，和 Android 运行时权限。
     * 只做前者，页面的摄像头会打开失败；只做后者，请求一直挂着。
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val needed = request.resources
            .mapNotNull(::androidPermissionFor)
            .distinct()
        val missing = needed.filter { permission ->
            context.checkSelfPermission(permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            runCatching { request.grant(request.resources) }
        } else {
            callbacks.requestPermissions(request, missing.toTypedArray())
        }
    }

    override fun onShowCustomView(
        view: View,
        callback: CustomViewCallback,
    ) {
        callbacks.showFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        callbacks.hideFullscreen()
    }
}
