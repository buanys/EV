package ev.buan.plugins.agent.browse

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
 * 浏览面板的挂载点：Compose 状态 + `AndroidView` + 全屏覆盖层。
 *
 * 这个文件只做三件事：
 *
 * 1. **两段式请求的状态**——文件选择器和运行时权限都是"发出去 → 等 Activity 回来"，
 *    回调必须先存住，两段之间的那段空隙只有 Compose 侧能守；
 * 2. **全屏覆盖层**——页面给的视图要参与重组才画得出来，所以它的状态必须留在这里；
 * 3. **挂上/放下那个 WebView**——`AndroidView` 的 factory 与 `DisposableEffect`。
 *
 * 回调本身（哪个事件该干什么、返回 true 还是 false）在 [BrowserClients]，
 * 平台开关（JS、混合内容、多窗口、外部 scheme、权限映射、证书文案）在 [BrowserPlatform]。
 * 这个文件里不应该再出现"这个站点为什么不能用"的判断——只出现"状态挂在哪"。
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
    val fullscreen = remember { FullscreenHost() }

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

    /**
     * 回调板：四件事在这里落地，其余在 [browserChromeClient] / [browserWebViewClient]。
     *
     * `remember` 是为了让客户端拿到的始终是同一个实例——它们是 WebView 的属性，只在 factory
     * 里赋一次值；重建实例不会换掉 WebView 上那个，只会让人以为它变了。
     */
    val callbacks = remember {
        object : BrowserCallbacks {
            override fun requestFile(
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean {
                // 上一次没回的必须先结掉：WebView 同一时刻只允许一个文件选择请求。
                pendingFile.value?.onReceiveValue(null)
                pendingFile.value = callback
                return runCatching {
                    filePicker.launch(params.createIntent())
                    true
                }.getOrElse {
                    // 选择器都没起来：这次请求就当用户取消，否则这个页面再也弹不出来。
                    pendingFile.value = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            override fun requestPermissions(
                request: PermissionRequest,
                androidPermissions: Array<String>,
            ) {
                pendingPermission.value = request
                permissionAsker.launch(androidPermissions)
            }

            override fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                fullscreen.show(view, callback)
            }

            override fun hideFullscreen() {
                fullscreen.hide()
            }
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    configureWebView(this)
                    webViewClient = browserWebViewClient(session)
                    webChromeClient = browserChromeClient(context, session, callbacks)
                    attachWebViewListeners(this, session)
                    session.attach(this)
                }
            },
        )

        // 全屏视频：一个盖住整块面板的黑底容器，页面给的视图挂进去。
        // 用单独的 AndroidView 而不是往当前布局里塞，是为了让 WebView 的位置和尺寸不变——
        // 挪动 WebView 会让网页重新排版，退出全屏时看着像跳了一下。
        val overlay = fullscreen.view
        if (overlay != null) {
            BackHandler { fullscreen.leave() }
            AndroidView(
                modifier = Modifier.fillMaxSize().background(UiColor.Black),
                factory = { ctx -> FrameLayout(ctx) },
                update = { host ->
                    if (overlay.parent !== host) {
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                        host.addView(
                            overlay,
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
            fullscreen.leave()
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
 * 全屏覆盖层的状态：页面给的视图 + 它等着我们回答的回调。
 *
 * 三个动作的差别不是随手写的：
 *
 * - [show] 页面要全屏：先摘掉上一块。同一时刻只允许一个自定义视图，两个 view 会抢父容器。
 * - [hide] 页面**自己**退出（视频播完）：只清状态，不回声——这一支本来就是 WebView 在
 *   通知我们"我退出了"，再回调 `onCustomViewHidden()` 会互相重入。
 * - [leave] 用户按返回键 / 面板要拆掉：这条得先告诉 WebView 一声，否则它以为还全屏着。
 */
private class FullscreenHost {
    var view by mutableStateOf<View?>(null)
        private set

    var callback by mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
        private set

    fun show(view: View, callback: WebChromeClient.CustomViewCallback) {
        detach()
        this.view = view
        this.callback = callback
    }

    fun hide() {
        detach()
        view = null
        callback = null
    }

    fun leave() {
        callback?.let { callback -> runCatching { callback.onCustomViewHidden() } }
        hide()
    }

    private fun detach() {
        view?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
    }
}
