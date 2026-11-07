package ev.buan.plugins.agent.browse

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 浏览器面板的全部可变状态，以及能对它做的操作。
 *
 * 把这些从 Composable 里搬出来，是为了让「页面怎么组装」和「页面处于什么状态」分开：
 * 之前 `BrowserPage` 一个函数里同时有 8 个 `remember`、WebView 回调、顶栏布局和生命周期清理，
 * 想加一个新能力（新窗口、错误页、下载）就得在同一个函数里再挤一块。
 *
 * ## 为什么 WebView 不是 Compose state
 *
 * 它只是被操作的对象，变了也不该触发重组——把它放进 `mutableStateOf` 会让每次页面跳转
 * 都多一轮无意义的重组。`view` 保持普通字段，UI 通过下面的只读状态观察它。
 *
 * ## 线程
 *
 * 所有方法都在主线程调用（Compose 事件与 WebView 回调都在主线程），因此没有加锁。
 */
@Stable
internal class BrowserSession(
    /** 标签 id，由 [BrowserTabs] 发；0 表示"还没挂上标签"（不该出现）。 */
    val id: Long = 0L,
) {

    /** 这个标签自己的 WebView；离开页面时只 detach，不销毁（见 [dispose]）。 */
    var view: WebView? = null
        private set

    /** 监听器只挂一次：`AndroidView.update` 会在每次重组时跑，重复 addJavascriptInterface 之类的会叠加。 */
    var listenersAttached: Boolean = false


    /** 地址栏里的文本。用户编辑期间不会被页面跳转覆盖。 */
    var address by mutableStateOf("")

    /** 当前页面的真实 URL；空表示还没打开过任何页面。 */
    var currentUrl by mutableStateOf("")
        private set

    var pageTitle by mutableStateOf("")
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)

    /**
     * 主框架的真实加载进度，0..100；0 表示没有在加载。
     *
     * 旧版进度线是一条来回摆动的假动画：它只能回答「还活着吗」，回答不了「还要多久」。
     * `onProgressChanged` 一直都在给真值，只是没人接。
     */
    var progress by mutableStateOf(0)
        private set

    /** 当前地址是否走 HTTPS。空地址（还没打开页面）时为 false。 */
    val secure: Boolean get() = currentUrl.startsWith("https://", ignoreCase = true)

    /** 地址栏是否有焦点。有焦点时不覆盖用户正在输入的内容。 */
    var editingAddress by mutableStateOf(false)

    /** 加载失败的说明；null 表示当前没有错误。 */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 本次会话访问过的域名，最近的在前。
     *
     * 只做内存、不落盘：这是首页的"快捷入口"，不是一个完整的访问历史。
     * 落盘的历史是另一件事（要能搜索、清除、按时间分组），先不假装有。
     */
    val recentHosts: List<String> get() = recent
    private val recent = androidx.compose.runtime.mutableStateListOf<String>()

    fun attach(view: WebView) {
        this.view = view
        syncControls()
    }

    fun detach() {
        // 只放下视图，不销毁：WebView 里还有页面、JS 上下文和滚动位置，下次回来接着用。
        view?.let { web -> (web.parent as? android.view.ViewGroup)?.removeView(web) }
        view = null
        listenersAttached = false
    }

    /**
     * 销毁这个标签的 WebView。
     *
     * 只有两处调用：用户关掉这个标签、插件停用。`BrowserTabs` 保证不会漏。
     */
    fun dispose() {
        view?.let { web ->
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            runCatching {
                web.stopLoading()
                web.webViewClient = android.webkit.WebViewClient()
                web.webChromeClient = android.webkit.WebChromeClient()
                web.destroy()
            }
        }
        view = null
        listenersAttached = false
    }

    /** 从 WebView 读回前进/后退能力。页面每次跳转后都要调。 */
    fun syncControls() {
        val view = view ?: return
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    /**
     * 打开 [input]（会先规范化）。
     *
     * @return 是否真的发起了加载；地址为空或面板还没挂上视图时返回 false。
     */
    fun open(input: String): Boolean {
        val view = view ?: return false
        val target = normalizeUrl(input) ?: return false
        address = target
        view.loadUrl(target)
        return true
    }

    /** @return 是否消费了这次返回（还有历史可退）。 */
    fun goBack(): Boolean {
        val view = view ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        syncControls()
        return true
    }

    /** @return 是否真的前进了。 */
    fun goForward(): Boolean {
        val view = view ?: return false
        if (!view.canGoForward()) return false
        view.goForward()
        syncControls()
        return true
    }

    fun reload() {
        view?.reload()
    }

    /** 从一个已知 URL 导航（新窗口、重定向都走这里）。 */
    fun navigate(url: String) {
        val view = view ?: return
        address = url
        error = null
        view.loadUrl(url)
    }

    /** 回到首页：清空页面状态，WebView 停在 about:blank。 */
    fun goHome() {
        val view = view ?: return
        view.stopLoading()
        view.loadUrl(HOME_URL)
        currentUrl = ""
        pageTitle = ""
        address = ""
        error = null
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
    }

    /** 页面开始加载。 */
    fun onStarted(url: String?) {
        // about:blank 是首页的占位，不是"用户打开了 about:blank"。
        if (url == HOME_URL) return
        loading = true
        error = null
        progress = 0
        if (!url.isNullOrBlank()) currentUrl = url
        syncControls()
    }

    /**
     * WebView 报上来的真实加载进度。
     *
     * 100 就意味着「这一页的东西都到了」，所以顺手把 loading 收掉：某些站点不发
     * onPageFinished（或发得比进度更晚），只靠它会让进度条一直挂着。
     */
    fun onProgress(value: Int) {
        val next = value.coerceIn(0, 100)
        progress = next
        if (next >= 100) loading = false
    }

    /** 页面加载结束：退出 loading，同步地址栏（用户正在编辑时不覆盖）。 */
    fun onFinished(url: String?, title: String?) {
        if (url == HOME_URL) return
        loading = false
        progress = 100
        if (!url.isNullOrBlank()) {
            currentUrl = url
            if (!editingAddress) address = url
        }
        if (!title.isNullOrBlank()) pageTitle = title
        rememberHost(url)
        syncControls()
    }

    /** 记下这个页面的域名；重复访问会被提到最前。 */
    private fun rememberHost(url: String?) {
        val host = url
            ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: return
        recent.remove(host)
        recent.add(0, host)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
    }

    /** 页面标题回调；WebChromeClient 可能在 onPageFinished 之前就送来。 */
    fun onTitle(title: String?) {
        if (!title.isNullOrBlank()) pageTitle = title
    }

    private companion object {
        const val HOME_URL = "about:blank"
        const val MAX_RECENT = 4
    }

    /**
     * 主框架加载失败。
     *
     * 只处理主框架：子资源（图片、广告）失败不该把整页变成错误页——那会让一个
     * 本来能读的页面看起来完全坏了。
     */
    fun onFailed(reason: String) {
        loading = false
        error = reason
    }

    fun clearError() {
        error = null
    }
}
