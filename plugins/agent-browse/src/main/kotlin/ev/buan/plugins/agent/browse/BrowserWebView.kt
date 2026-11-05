package ev.buan.plugins.agent.browse

import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
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
 * 页面级的策略（要不要留外部浏览器、允不允许文件访问）集中在这里，UI 那边不用知道。
 * 以后要加的新窗口、下载、JS 对话框、错误页回调，也都应该落在这个文件里，
 * 而不是回到 BrowserPage 那个组装函数。
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
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        session.onTitle(title)
                    }
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
