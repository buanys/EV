package ev.buan.plugins.agent.browse

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cordis.api.BrowserService
import org.cordis.api.BrowserSnapshot
import org.cordis.api.BrowserTabState
import org.cordis.api.BrowserUi

/**
 * 浏览器界面的那一层：**宿主引擎的遥控器 + 一面镜子**。
 *
 * 页面本身不在这里了。以前这个插件自己建 WebView、自己装回调、自己管标签，于是同一台
 * 手机上跑着两套浏览器：agent 打开的那一页用户看不见，用户点开的那一页 agent 摸不着。
 * 现在 WebView 归宿主（[BrowserService]），这里只剩三件事：
 *
 *  1. **镜像宿主的状态**——[snapshot] 就是标签条、地址栏、进度线的数据源；
 *  2. **把界面的点击翻成宿主的调用**——开/切/关窗口、导航、刷新；
 *  3. **替宿主回答只有界面才答得出的问题**——选文件、运行时权限、全屏（[BrowserUi]）。
 *
 * 这也是"退出浏览器页再回来，页面还在"的答案：真正的页面在宿主里，界面退出只
 * `releaseUi()`，窗口一个不少。这个对象活着只是为了让地址栏、最近站点这些**界面记忆**
 * 不因为一次进出而丢掉。
 *
 * 线程：所有方法都在主线程调用（Compose 事件、宿主回调都在主线程），因此没有加锁。
 */
@Stable
internal class BrowserPanel(
    private val service: BrowserService,
    private val scope: CoroutineScope,
) : BrowserUi {

    /** 宿主报上来的窗口状态。界面的一切都从它派生。 */
    var snapshot by mutableStateOf(service.snapshot())
        private set

    /** 当前窗口；没有窗口时为 null。 */
    val active: BrowserTabState? get() = snapshot.active

    /** 地址栏里的文本。用户编辑期间不被页面跳转覆盖。 */
    var address by mutableStateOf("")

    /** 地址栏是否处于编辑态。 */
    var editing by mutableStateOf(false)

    /** 本次会话访问过的域名，最近的在前。只做内存，不做访问历史。 */
    val recentHosts: List<String> get() = recent
    private val recent = mutableStateListOf<String>()

    /** 全屏视图（视频）；null 表示没有。 */
    var fullscreenView by mutableStateOf<View?>(null)
        private set

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var attached = false
    private var lastSeenUrl = ""

    /**
     * 选文件 / 运行时权限都要 Activity 结果，而只有处在组合里的页面拿得到 launcher。
     * 页面进来时把这两个槽填上，离开时清空——槽空着的时候宿主按"用户取消"处理。
     */
    var filePicker: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    var permissionAsker: ((PermissionRequest, Array<String>) -> Unit)? = null

    /** 当前地址是否走 HTTPS。 */
    val secure: Boolean get() = active?.url?.startsWith("https://", ignoreCase = true) == true

    // ------------------------------------------------------------------ 宿主的回调

    override fun onSnapshot(snapshot: BrowserSnapshot) {
        this.snapshot = snapshot
        val tab = snapshot.active
        if (tab == null) {
            if (!editing) address = ""
            return
        }
        if (!editing) address = tab.url
        rememberHost(tab.url)
    }

    override fun requestFile(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        val picker = filePicker ?: run {
            // 界面不在：按"用户取消"结掉，否则这个页面以后再也弹不出选择器。
            runCatching { callback.onReceiveValue(null) }
            return false
        }
        return picker(callback, params)
    }

    override fun requestPermissions(request: PermissionRequest, androidPermissions: Array<String>) {
        val asker = permissionAsker
        if (asker == null) {
            runCatching { request.deny() }
            return
        }
        asker(request, androidPermissions)
    }

    override fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        detachFullscreenView()
        fullscreenView = view
        fullscreenCallback = callback
    }

    override fun hideFullscreen() {
        detachFullscreenView()
        fullscreenView = null
        fullscreenCallback = null
    }

    /** 用户按返回键退出全屏：先告诉 WebView 一声，再收起来（与页面自己退出是两条路）。 */
    fun leaveFullscreen() {
        fullscreenCallback?.let { callback -> runCatching { callback.onCustomViewHidden() } }
        hideFullscreen()
    }

    private fun detachFullscreenView() {
        fullscreenView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 界面接管：页面搬到眼前，状态与弹窗连到这里来。 */
    fun attach() {
        if (attached) return
        attached = true
        service.attachUi(this)
    }

    /** 界面离开：只放下视图，窗口与页面继续活着。 */
    fun release() {
        if (!attached) return
        attached = false
        leaveFullscreen()
        service.releaseUi()
    }

    /** 宿主唯一的那块容器；界面把它塞进 `AndroidView`。 */
    fun surface(): View = service.surface()

    /** 骨架：一个窗口都没有时先开一个，否则界面没有东西可画。 */
    fun ensureTab() {
        if (service.snapshot().tabs.isEmpty()) newTab()
    }

    // ------------------------------------------------------------------ 动作

    fun open(input: String) {
        val target = normalizeUrl(input) ?: return
        editing = false
        address = target
        scope.launch { runCatching { service.open(target) } }
    }

    fun newTab() {
        scope.launch { runCatching { service.openTab() } }
    }

    fun selectTab(id: Long) {
        scope.launch { runCatching { service.selectTab(id) } }
    }

    fun closeTab(id: Long) {
        scope.launch { runCatching { service.closeTab(id) } }
    }

    fun reload() {
        scope.launch { runCatching { service.reload() } }
    }

    fun back() {
        scope.launch { runCatching { service.back() } }
    }

    fun forward() {
        scope.launch { runCatching { service.forward() } }
    }

    /** 回首页：把当前窗口重置成空白页。 */
    fun goHome() {
        scope.launch { runCatching { service.open(HOME_URL) } }
    }

    /** 记下这个页面的域名；重复访问会被提到最前。 */
    private fun rememberHost(url: String) {
        if (url == lastSeenUrl) return
        lastSeenUrl = url
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: return
        recent.remove(host)
        recent.add(0, host)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
    }

    private companion object {
        const val HOME_URL = "about:blank"
        const val MAX_RECENT = 4
    }
}
