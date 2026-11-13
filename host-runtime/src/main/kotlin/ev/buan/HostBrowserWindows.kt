package ev.buan

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * 窗口（标签页）的生灭，以及那个容器的两处落脚点。
 *
 * 与 [HostBrowserSession] 分开的理由是长度，不是概念：会话那边已经在管接口、导航与状态，
 * 再塞进"怎么建 WebView、容器搬到哪"就会变成一个读不完的文件。
 *
 * 三条纪律：
 *
 *  1. **一个窗口一个 WebView，只有当前窗口 VISIBLE**。用 GONE 而不是销毁/重建：
 *     切回来时页面、JS 上下文、滚动位置都还在。
 *  2. **销毁只发生在两处**：用户/agent 关掉这个窗口、内存压力。其余时候只切可见性。
 *  3. **容器只有一个**。它要么在界面的布局里，要么在屏幕外——不会两头都有，
 *     也不会两头都没有（那意味着页面不再渲染）。
 */

/** 容器；第一次要它的时候建。只在主线程调用。 */
internal fun HostBrowserSession.containerOnMain(): FrameLayout =
    container ?: FrameLayout(appContext).also { container = it }

/** 当前窗口；一个都没有就开一个。 */
internal fun HostBrowserSession.activeOnMain(): BrowserTab {
    val existing = tabs.firstOrNull { it.id == activeId }
    if (existing != null) {
        ensureOffscreenOnMain()
        return existing
    }
    return createTabOnMain()
}

/** 开一个空白窗口并切过去。URL 由调用方随后 load。 */
internal fun HostBrowserSession.createTabOnMain(): BrowserTab {
    val tab = BrowserTab(nextId++)
    val web = createWebView(tab)
    tab.web = web
    containerOnMain().addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    tabs += tab
    activateOnMain(tab)
    ensureOffscreenOnMain()
    return tab
}

/**
 * 没有界面时，保证容器挂在**当前** Activity 上。
 *
 * Activity 会被重建（转屏、从后台回来、页面退出）：旧的装饰视图带着容器一起没了，
 * 容器还好好地抱着一堆 WebView，只是不在任何窗口里——不重新挂上去，页面就不再绘制，
 * 截图会是一张空白，而那看起来像"我们的浏览器坏了"。
 *
 * 界面接管时不碰：那时候容器归界面，搬家会把用户眼前那一页抽走。
 */
internal fun HostBrowserSession.ensureOffscreenOnMain() {
    if (uiAttached()) return
    if (hostActivity() == null) return
    val host = ensureWindowHost(hostActivity() ?: return)
    if (container?.parent === host) return
    attachToWindowHost()
}

/** 切到某个窗口：可见性一换，那一页就在眼前了。 */
internal fun HostBrowserSession.activateOnMain(tab: BrowserTab) {
    activeId = tab.id
    tabs.forEach { other ->
        other.web?.visibility = if (other.id == tab.id) View.VISIBLE else View.GONE
    }
    refreshControls(tab)
}

internal fun HostBrowserSession.refreshControls(tab: BrowserTab) {
    val web = tab.web ?: return
    tab.canGoBack = web.canGoBack()
    tab.canGoForward = web.canGoForward()
}

/** 真的销毁一个窗口的 WebView。 */
internal fun HostBrowserSession.destroyTab(tab: BrowserTab) {
    tab.pending?.cancel()
    tab.pending = null
    val web = tab.web ?: return
    (web.parent as? ViewGroup)?.removeView(web)
    runCatching {
        web.stopLoading()
        web.webViewClient = android.webkit.WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.destroy()
    }
    tab.web = null
}

/**
 * `target=_blank` / `window.open`：系统先给一个空 WebView 让我们把目标地址转走。
 *
 * 这里用**临时探针**接住第一跳（拿到 URL 就用不上了）：直接把空 WebView 当新窗口在
 * Android 上可行但要求它还没被加载过，探针这条路更稳，而且能明确决定"开新窗口还是顶掉
 * 当前页"——窗口满了就只能顶掉，总比点了没反应好。
 */
internal fun HostBrowserSession.onCreateWindowRequest(
    tab: BrowserTab,
    host: WebView,
    transport: WebView.WebViewTransport,
    result: android.os.Message?,
): Boolean {
    val message = result ?: return false
    var opened = false
    fun hand(url: String) {
        if (opened) return
        opened = true
        main.post { openPopupTab(url) }
    }
    val probe = WebView(host.context).apply {
        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                hand(request.url.toString())
                post { destroy() }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank() && !url.startsWith("about:")) {
                    hand(url)
                    post { destroy() }
                }
            }
        }
    }
    transport.webView = probe
    message.sendToTarget()
    return true
}

private fun HostBrowserSession.openPopupTab(url: String) {
    if (tabs.size >= HostBrowserSession.MAX_TABS) {
        tabs.firstOrNull { it.id == activeId }?.web?.loadUrl(url)
    } else {
        createTabOnMain().web?.loadUrl(url)
    }
    onChanged()
}

// ---------------------------------------------------------------------- 屏幕外的家

/** 界面离开之后，把容器放回屏幕外：页面继续渲染，agent 的下一步照样生效。 */
internal fun HostBrowserSession.attachToWindowHost() {
    val box = container ?: return
    val activity = hostActivity() ?: return
    val host = ensureWindowHost(activity)
    if (box.parent === host) return
    (box.parent as? ViewGroup)?.removeView(box)
    host.removeAllViews()
    host.addView(box, FrameLayout.LayoutParams(hostWidth, hostHeight))
}

/**
 * 那块屏幕外的宿主视图。
 *
 * `translationX` 而不是 `alpha=0`：透明 View 不参与绘制，`draw(Canvas)` 截图会是一张空白；
 * `translationX` 把它挪到画布外，它仍然在窗口树里、仍然按真实尺寸渲染。
 */
internal fun HostBrowserSession.ensureWindowHost(activity: Activity): FrameLayout {
    val existing = windowHost
    if (existing != null && existing.parent != null) return existing
    val metrics = activity.resources.displayMetrics
    hostWidth = metrics.widthPixels.coerceAtLeast(720)
    hostHeight = metrics.heightPixels.coerceAtLeast(1280)
    val host = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(hostWidth, hostHeight)
        translationX = -(hostWidth + 400f)
    }
    runCatching { (activity.window?.decorView as? ViewGroup)?.addView(host) }
    windowHost = host
    return host
}

/** 界面是否接管着：界面在时容器不能被搬回屏幕外，否则用户眼前那一页会忽然消失。 */
internal fun HostBrowserSession.uiAttached(): Boolean = browserUi != null
