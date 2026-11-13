package ev.buan

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.BrowserService
import org.cordis.api.BrowserSnapshot
import org.cordis.api.BrowserUi
import org.cordis.api.PageElement
import org.cordis.api.PageState
import org.json.JSONObject

/**
 * 内置浏览器的引擎：**一组常驻的 WebView，住在唯一一个容器里**。
 *
 * 与 [HostPageReader] 的分工很明确：那个是"取一次正文就走"，每次新建再销毁，快；
 * 这个是"连续操作一页"，要保留 cookie、DOM 与历史，因此必须常驻。
 *
 * ## 为什么容器只有一个
 *
 * 界面与 agent 必须摸到**同一个** WebView。两套 WebView 的结局是：agent 打开的页面
 * 用户看不见，用户点的链接 agent 摸不着——同一台机器两个世界。
 *
 * 所以这里只有一个 [FrameLayout]：所有窗口的 WebView 都长在里面，只有当前窗口可见。
 *
 *  - 没有界面时，容器挂在 Activity 装饰视图的**屏幕外左侧**。不是 `alpha=0` 的隐藏容器
 *    （那种 View 不参与绘制，`draw(Canvas)` 出来是空白），也不是 1×1（那样点击与滚动都没意义）。
 *  - 界面接管时（[surface]）整块搬进界面，于是用户眼前那一页就是 agent 操作的那一页。
 *  - 界面离开时（[releaseUi]）只搬回去，**不销毁任何东西**：窗口、页面、JS 上下文、
 *    滚动位置全在。
 *
 * 窗口本身的生灭在 [HostBrowserWindows]；WebView 回调在 [HostBrowserSessionEngine]；
 * 页面里的动作（抽元素、点、输入、截图）在 [HostBrowserPage]。
 */
internal class HostBrowserSession(
    internal val appContext: Context,
    private val activityProvider: () -> Activity?,
) : BrowserService {

    internal val main = Handler(Looper.getMainLooper())

    internal val tabs = mutableListOf<BrowserTab>()
    internal var activeId = 0L
    internal var nextId = 1L

    /** 所有窗口的 WebView 都长在这一个容器里。 */
    internal var container: FrameLayout? = null

    /** 容器在没有界面时的落脚处：Activity 装饰视图左侧屏幕外。 */
    internal var windowHost: FrameLayout? = null
    internal var hostWidth = 0
    internal var hostHeight = 0

    private var ui: BrowserUi? = null
    private var notifyPosted = false

    internal val browserUi: BrowserUi? get() = ui

    internal fun hostActivity(): Activity? = activityProvider()

    // ------------------------------------------------------------------ 快照

    override fun snapshot(): BrowserSnapshot = BrowserSnapshot(
        activeId = activeId,
        maxTabs = MAX_TABS,
        tabs = tabs.map(BrowserTab::state),
    )

    /** 状态变了。合并成一次通知：进度回调一秒能来十几次，界面上多次重组没有意义。 */
    internal fun onChanged() {
        if (notifyPosted) return
        notifyPosted = true
        main.post {
            notifyPosted = false
            ui?.onSnapshot(snapshot())
        }
    }

    // ------------------------------------------------------------------ 界面

    override fun surface(): View {
        check(Looper.myLooper() == Looper.getMainLooper()) { "surface() 必须在主线程调用" }
        val box = containerOnMain()
        // 同一个 View 只能有一个父容器：先把旧的摘下来（屏幕外的宿主，或上一次组合留下的
        // 已经废弃的 AndroidView 容器），界面才接得住。
        (box.parent as? android.view.ViewGroup)?.removeView(box)
        return box
    }

    override fun attachUi(ui: BrowserUi) {
        this.ui = ui
        main.post { ui.onSnapshot(snapshot()) }
    }

    override fun releaseUi() {
        ui = null
        main.post { attachToWindowHost() }
    }

    // ------------------------------------------------------------------ 窗口

    override suspend fun open(url: String): PageState = withContext(Dispatchers.Main) {
        val target = normalizeBrowserInput(url)
        navigateOnMain(activeOnMain(), target) { it.loadUrl(target) }
    }

    override suspend fun openTab(url: String?): PageState = withContext(Dispatchers.Main) {
        if (tabs.size >= MAX_TABS) {
            throw IllegalStateException("窗口已满（最多 $MAX_TABS 个），先关掉一个再开")
        }
        val target = url?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeBrowserInput)
        val tab = createTabOnMain()
        if (target == null) {
            onChanged()
            return@withContext PageState("", "", false, false, null, tab.id)
        }
        navigateOnMain(tab, target) { it.loadUrl(target) }
    }

    override suspend fun selectTab(id: Long) {
        withContext(Dispatchers.Main) {
            val tab = tabs.firstOrNull { it.id == id } ?: return@withContext
            activateOnMain(tab)
            onChanged()
        }
    }

    override suspend fun closeTab(id: Long) {
        withContext(Dispatchers.Main) {
            val index = tabs.indexOfFirst { it.id == id }
            if (index < 0) return@withContext
            val closing = tabs.removeAt(index)
            destroyTab(closing)
            if (activeId == id) {
                val next = tabs.getOrNull(index) ?: tabs.lastOrNull()
                if (next != null) activateOnMain(next) else activeId = 0L
            }
            onChanged()
        }
    }

    /** 销毁全部窗口；插件停用或内存告急时调用。 */
    fun close() {
        main.post {
            tabs.forEach(::destroyTab)
            tabs.clear()
            activeId = 0L
            container?.let { box -> (box.parent as? android.view.ViewGroup)?.removeView(box) }
            container = null
            windowHost?.let { host ->
                (host.parent as? android.view.ViewGroup)?.removeView(host)
                host.removeAllViews()
            }
            windowHost = null
            onChanged()
        }
    }

    /**
     * 内存压力：界面还在就只丢**其它**窗口，界面没了就全丢。
     *
     * 旧版这里直接 `close()`：用户正看着一页，系统发一次 `TRIM_MEMORY_RUNNING_LOW`，
     * 页面就白了。丢弃必须让"眼前这一页"例外。
     */
    fun trimMemory() {
        main.post {
            val keep = if (ui != null) tabs.firstOrNull { it.id == activeId } else null
            val dropped = tabs.filter { it.id != keep?.id }
            dropped.forEach(::destroyTab)
            tabs.removeAll(dropped.toSet())
            if (tabs.isEmpty()) {
                activeId = 0L
            } else if (tabs.none { it.id == activeId }) {
                activateOnMain(tabs.first())
            }
            onChanged()
        }
    }

    // ------------------------------------------------------------------ 导航

    override suspend fun state(): PageState = withContext(Dispatchers.Main) {
        stateNow(activeOnMain())
    }

    override suspend fun readable(maxChars: Int): String = readPage(maxChars, readable = true)

    override suspend fun text(maxChars: Int): String = readPage(maxChars, readable = false)

    override suspend fun elements(limit: Int): List<PageElement> = collectElements(limit)

    override suspend fun click(index: Int): PageState = clickElement(index)

    override suspend fun type(index: Int, text: String, submit: Boolean): PageState =
        typeIntoElement(index, text, submit)

    override suspend fun scroll(direction: String, factor: Double): PageState =
        scrollPage(direction, factor)

    override suspend fun screenshot(): String? = capturePage()

    override suspend fun waitFor(selector: String, timeoutMs: Long): Boolean =
        awaitSelector(selector, timeoutMs)

    override suspend fun navigateInPlace(url: String) {
        withContext(Dispatchers.Main) {
            activeOnMain().web?.loadUrl(normalizeBrowserInput(url))
            onChanged()
        }
    }

    override suspend fun reload(): PageState = withContext(Dispatchers.Main) {
        val tab = activeOnMain()
        navigateOnMain(tab, tab.url.ifBlank { null }) { it.reload() }
    }

    override suspend fun back(): PageState = historyStep(-1)

    override suspend fun forward(): PageState = historyStep(1)

    /**
     * 前进/后退：能走 WebView 自己的历史就走，否则走页面自己的 `history`。
     *
     * 两条都要：用户手点出来的链接是**真的**导航（`canGoBack()` 有值）；而 agent 的点击是
     * 用 `location.href = ...` 驱动的，那些记录在页面 history 里，WebView 的 `canGoBack()`
     * 在这条路径上始终是 false（实测）。只认一种，就会有一半"退不回去"。
     */
    private suspend fun historyStep(delta: Int): PageState = withContext(Dispatchers.Main) {
        val tab = activeOnMain()
        val web = tab.web ?: return@withContext PageState("", "", false, false, "窗口不可用", tab.id)
        val direct = if (delta < 0) web.canGoBack() else web.canGoForward()
        if (!direct) {
            val length = evaluate(web, "(function(){return String(history.length)})()")
                ?.toIntOrNull() ?: 1
            if (length <= 1) return@withContext stateNow(tab)
        }
        val done = CompletableDeferred<Unit>()
        tab.pending = done
        if (direct) {
            if (delta < 0) web.goBack() else web.goForward()
        } else {
            evaluate(web, "(function(){history.go($delta);return 'ok'})()")
        }
        withTimeoutOrNull(HISTORY_TIMEOUT_MS) { done.await() }
        tab.pending = null
        delay(SETTLE_DELAY_MS)
        stateNow(tab)
    }

    internal suspend fun navigateOnMain(
        tab: BrowserTab,
        target: String?,
        action: (android.webkit.WebView) -> Unit,
    ): PageState {
        val web = tab.web
            ?: return PageState(target.orEmpty(), "", false, false, "窗口不可用", tab.id)
        val done = CompletableDeferred<Unit>()
        tab.pending = done
        runCatching { action(web) }.onFailure { error ->
            tab.pending = null
            return PageState(target.orEmpty(), "", false, false, "导航失败：${error.message}", tab.id)
        }
        withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
        tab.pending = null
        // SPA 在 onPageFinished 之后还有一轮渲染，等一拍再读状态。
        delay(SETTLE_DELAY_MS)
        return stateNow(tab)
    }

    /**
     * 读一次页面的真值：地址、标题、历史长度。
     *
     * `history.length` 也读，是因为"能不能后退"不能只信 `canGoBack()`：agent 的点击是
     * `location.href` 驱动的，那条路上 WebView 认为没有历史。
     */
    internal suspend fun stateNow(tab: BrowserTab): PageState {
        val web = tab.web ?: return PageState("", "", false, false, "窗口不可用", tab.id)
        val payload = evaluate(
            web,
            "(function(){return JSON.stringify({url:location.href,title:document.title||'',history:history.length})})()",
        )
        runCatching { JSONObject(payload.orEmpty()) }.getOrNull()?.let { json ->
            val url = json.optString("url").ifEmpty { web.url.orEmpty() }
            tab.url = if (url.startsWith("about:blank")) "" else url
            json.optString("title").takeIf { it.isNotBlank() }?.let { tab.title = it }
            tab.canGoBack = web.canGoBack() || json.optInt("history", 1) > 1
            tab.canGoForward = web.canGoForward()
        }
        onChanged()
        return PageState(
            url = tab.url,
            title = tab.title,
            canGoBack = tab.canGoBack,
            canGoForward = tab.canGoForward,
            error = tab.error,
            tabId = tab.id,
        )
    }

    internal companion object {
        /** 一个窗口 ≈ 一份 WebView 内存；再多就该让用户/agent 先关几个了。 */
        const val MAX_TABS = 5
        const val NAV_TIMEOUT_MS = 20_000L
        const val HISTORY_TIMEOUT_MS = 4_000L
        const val SETTLE_DELAY_MS = 500L
    }
}
