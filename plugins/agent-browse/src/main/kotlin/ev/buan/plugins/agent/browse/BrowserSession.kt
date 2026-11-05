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
internal class BrowserSession {

    /** 当前挂着的平台视图；面板销毁后为 null。 */
    var view: WebView? = null
        private set

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
        view = null
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
        canGoBack = false
        canGoForward = false
    }

    /** 页面开始加载。 */
    fun onStarted(url: String?) {
        // about:blank 是首页的占位，不是"用户打开了 about:blank"。
        if (url == HOME_URL) return
        loading = true
        error = null
        if (!url.isNullOrBlank()) currentUrl = url
        syncControls()
    }

    /** 页面加载结束：退出 loading，同步地址栏（用户正在编辑时不覆盖）。 */
    fun onFinished(url: String?, title: String?) {
        if (url == HOME_URL) return
        loading = false
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
