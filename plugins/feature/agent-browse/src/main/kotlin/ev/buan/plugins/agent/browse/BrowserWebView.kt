package ev.buan.plugins.agent.browse

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 平台 WebView 的宿主：创建、配置、回调转发、销毁。
 *
 * 这个文件是「网页能用到什么程度」的总闸。手机上任何一个正常站点都会用到下面这些，
 * 少一个的表现都是"这个站点了没反应"，而不是报错：
 *
 * | 能力 | 少了它 | 回调 |
 * |---|---|---|
 * | 新窗口 | `target=_blank` 点了没反应 | `onCreateWindow` |
 * | 加载失败 | 断网白屏 | `onReceivedError` |
 * | JS 对话框 | `alert` 挂住后续脚本 | `onJsAlert` / `onJsConfirm` / `onJsPrompt` |
 * | 文件上传 | 选择器不弹 | `onShowFileChooser` |
 * | 摄像头/麦克风 | `getUserMedia` 永远 pending | `onPermissionRequest` |
 * | 全屏视频 | 只能在小窗口里看 | `onShowCustomView` |
 * | 下载 | 点了无声无息 | `setDownloadListener` |
 * | 证书错误 | 白屏，且默认行为不可见 | `onReceivedSslError` |
 *
 * 另外两件"看不见但一直在错"的事：进度条用的是假动画（真值在 `onProgressChanged`），
 * 返回按钮的可用状态只在 `onPageFinished` 同步（真正的时机是 `doUpdateVisitedHistory`）。
 */
@Composable
internal fun BrowserWebView(
    session: BrowserSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 文件选择与网页权限请求都是"发出去 → 等 Activity 回来"的两段式，回调必须先存住。
    val pendingFile = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingPermission = remember { mutableStateOf<PermissionRequest?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    /** 页面自己结束全屏（视频播完）：不回调，否则会和 WebView 互相重入。 */
    fun clearFullscreen() {
        customView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        customView = null
        customCallback = null
    }

    /** 用户按返回、或面板要拆掉时退出全屏：先告诉 WebView 一声。 */
    fun leaveFullscreen() {
        customCallback?.let { callback -> runCatching { callback.onCustomViewHidden() } }
        clearFullscreen()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFile.value
        pendingFile.value = null
        // parseResult 在取消时返回 null——这正好是 WebView 要求的那句"用户没选"。
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    val permissionAsker = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val request = pendingPermission.value
        pendingPermission.value = null
        if (request == null) return@rememberLauncherForActivityResult
        val resources = request.resources
        val refused = resources.any { resource ->
            androidPermissionFor(resource)?.let { granted[it] != true } == true
        }
        // 页面可能已经跳走了，这时的 grant/deny 会抛；吞掉比崩掉好。
        runCatching { if (refused) request.deny() else request.grant(resources) }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
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
                    setBackgroundColor(Color.TRANSPARENT)

                    webViewClient = object : WebViewClient() {
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

                    webChromeClient = object : WebChromeClient() {
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
                         */
                        override fun onShowFileChooser(
                            view: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            pendingFile.value?.onReceiveValue(null)
                            pendingFile.value = filePathCallback
                            return runCatching {
                                filePicker.launch(fileChooserParams.createIntent())
                                true
                            }.getOrElse {
                                pendingFile.value = null
                                filePathCallback.onReceiveValue(null)
                                false
                            }
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
                                pendingPermission.value = request
                                permissionAsker.launch(missing.toTypedArray())
                            }
                        }

                        override fun onShowCustomView(
                            view: View,
                            callback: CustomViewCallback,
                        ) {
                            // 同一时刻只允许一个自定义视图；旧的先摘掉，否则两个 view 抢父容器。
                            customView?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
                            customView = view
                            customCallback = callback
                        }

                        override fun onHideCustomView() {
                            clearFullscreen()
                        }
                    }

                    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                        enqueueDownload(context, url, contentDisposition, mimeType)
                    }

                    // 长按链接：能复制、能分享。没有它，长按会落在文字选择上，
                    // 而"我想把这个链接发出去"是这个面板里最常发生的事之一。
                    setOnLongClickListener { view ->
                        // 这个回调的入参是 View，不是 WebView；不转一下就拿不到 hitTestResult。
                        val web = view as? WebView ?: return@setOnLongClickListener false
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

                    session.attach(this)
                }
            },
        )

        // 全屏视频：一个盖住整块面板的黑底容器，页面给的视图挂进去。
        // 用单独的 AndroidView 而不是往当前布局里塞，是为了让 WebView 的位置和尺寸不变——
        // 挪动 WebView 会让网页重新排版，退出全屏时看着像跳了一下。
        val fullscreen = customView
        if (fullscreen != null) {
            BackHandler { leaveFullscreen() }
            AndroidView(
                modifier = Modifier.fillMaxSize().background(UiColor.Black),
                factory = { ctx -> FrameLayout(ctx) },
                update = { host ->
                    if (fullscreen.parent !== host) {
                        (fullscreen.parent as? ViewGroup)?.removeView(fullscreen)
                        host.addView(
                            fullscreen,
                            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
                        )
                    }
                },
            )
        }
    }

    // 面板关闭时销毁 WebView：先摘掉两个 client（它们捕获了页面状态），
    // 再从父容器移除，最后 destroy——挂在父容器上的 WebView 会泄漏。
    DisposableEffect(Unit) {
        onDispose {
            leaveFullscreen()
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

/** 面板自己处理、不交给系统的 scheme。 */
private val IN_PANEL_SCHEMES = setOf(
    "http", "https", "about", "data", "file", "content", "javascript", "blob",
)

/**
 * `tel:` `mailto:` `market:` `intent:` 交给系统。
 *
 * `intent://` 要单独解析：`ACTION_VIEW` 不认识它。解析出来的 Intent 必须清掉
 * component/selector——否则一个网页可以指定要启动哪个 Activity 的哪个组件。
 */
private fun openExternally(context: Context, uri: Uri): Boolean {
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
private fun androidPermissionFor(resource: String): String? = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

/** 证书错误说人话。只解释 "为什么不让你进"，不给"继续"这个选项。 */
private fun sslMessage(primaryError: Int): String = when (primaryError) {
    android.net.http.SslError.SSL_DATE_INVALID -> "这个网站的证书已经过期，连接不安全。"
    android.net.http.SslError.SSL_EXPIRED -> "这个网站的证书过期了，连接不安全。"
    android.net.http.SslError.SSL_IDMISMATCH -> "证书上的域名和这个网站的域名对不上，可能是伪装站点。"
    android.net.http.SslError.SSL_NOTYETVALID -> "这个网站的证书还没生效。"
    android.net.http.SslError.SSL_UNTRUSTED -> "签发这个网站证书的机构不被系统信任。"
    else -> "这个网站的证书有问题，连接不安全。"
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
    var answered = false
    fun answer(positive: Boolean) {
        if (answered) return
        answered = true
        if (positive) onPositive() else onDismiss()
    }
    AlertDialog.Builder(context)
        .setMessage(message)
        .setPositiveButton(positiveLabel) { _, _ -> answer(true) }
        .apply {
            if (negativeLabel != null) setNegativeButton(negativeLabel) { _, _ -> answer(false) }
        }
        .setOnCancelListener { answer(false) }
        .show()
}

/** `prompt()`：带一个输入框。取消和关闭都算"没输入过"。 */
private fun showPrompt(
    context: Context,
    message: String,
    defaultValue: String,
    onResult: (String?) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val inset = (16 * density).toInt()
    val input = EditText(context).apply {
        setText(defaultValue)
        setSelection(text.length)
        setSingleLine()
    }
    val container = FrameLayout(context).apply {
        addView(
            input,
            FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(inset, inset / 2, inset, 0)
            },
        )
    }
    var answered = false
    fun answer(value: String?) {
        if (answered) return
        answered = true
        onResult(value)
    }
    AlertDialog.Builder(context)
        .setMessage(message)
        .setView(container)
        .setPositiveButton("确定") { _, _ -> answer(input.text?.toString().orEmpty()) }
        .setNegativeButton("取消") { _, _ -> answer(null) }
        .setOnCancelListener { answer(null) }
        .show()
}

/** 长按网页里的链接：复制 / 分享。 */
private fun showLinkMenu(context: Context, url: String, title: String) {
    val items = arrayOf("复制链接", "分享")
    AlertDialog.Builder(context)
        .setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("链接", url))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }

                1 -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title\n$url")
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(send, null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        .setOnCancelListener { }
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
                fileNameFor(url, contentDisposition, mimeType),
            )
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        }
    }.getOrNull() ?: return
    runCatching {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}

/**
 * `attachment; filename="x.pdf"` → `x.pdf`；否则取 URL 最后一段。
 *
 * 两处都拿不到扩展名时按 MIME 补一个：`application/pdf` 存成 `download` 的话，
 * 下载完成后系统不知道用什么打开，用户只看到一个没有图标的文件。
 */
private fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
    val fromHeader = contentDisposition
        ?.substringAfter("filename=", "")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotBlank() }
    if (fromHeader != null) return fromHeader

    val fromUrl = url.substringAfterLast('/')
        .substringBefore('?')
        .takeIf { it.isNotBlank() && !it.contains(':') }
    if (fromUrl != null && '.' in fromUrl.substringAfterLast('/')) return fromUrl

    val extension = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    val base = fromUrl ?: "download"
    return if (extension != null && !base.endsWith(".$extension")) "$base.$extension" else base
}
