package org.cordis.api

import android.net.Uri
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import org.cordis.ServiceKey

/**
 * 内置浏览器：**宿主里的一台引擎**，插件界面只是它的壳。
 *
 * 为什么必须长在宿主：一个可交互的页面需要一个**持续存在**的 WebView（保留 cookie、DOM 与
 * 历史），而 WebView 只能在主线程创建、需要一个 `Context`——插件两样都没有。
 * `PageReader` 那种"每次新建一个、取完立刻销毁"的做法适合抓正文，不适合连续操作。
 *
 * 为什么界面也要接这里（而不是自己造一个 WebView）：同一台引擎，两只手在摸。agent 点了一个
 * 链接，用户眼前的那一页就该跳过去；用户切到另一个窗口，agent 的 `browser_state` 也该报那个
 * 窗口。两套 WebView 只会变成"我看到的和你看到的不是一回事"。
 *
 * 四条约定：
 *
 *  1. **窗口（标签页）是引擎的状态**，不是界面的状态。界面退出、重组、被销毁重建，窗口都还在；
 *     只有 [closeTab]、插件停用与内存压力才会真的销毁一个 WebView。
 *  2. **元素用下标寻址**，不是选择器。`find_elements` 给出一张带序号的表（并同时给出选择器），
 *     `click` / `type` 用的是那个序号；页面的 DOM 变了，序号就作废，下一次 `find_elements` 重排。
 *     让模型自己拼 CSS 选择器是幻觉的高发区。
 *  3. **每次操作后都返回新的 [PageState]**，调用方不必再问一次"现在在哪"。
 *  4. **工具的每一个动作都作用于当前窗口**（[BrowserSnapshot.activeId]）。要并行开两页就
 *     [openTab]，再用 [selectTab] 切换。
 */
interface BrowserService {

    // ------------------------------------------------------------------ 窗口

    /**
     * 所有窗口的当前状态。任何线程都能调，不触发加载、不等页面。
     *
     * 界面靠它画标签条，工具靠它回答"现在有几个窗口"。
     */
    fun snapshot(): BrowserSnapshot

    /** 在**当前窗口**打开网址（或 `about:blank` 重置），等页面加载完成再返回。 */
    suspend fun open(url: String): PageState

    /**
     * 开一个新窗口并切过去；[url] 为空时是一个空白窗口。
     *
     * 窗口数有上限（见 [BrowserSnapshot.maxTabs]）：一个窗口就是一个 WebView，几 MB。
     * 满了再开抛 [IllegalStateException]——调用方要如实说一句人话，而不是静默失败。
     */
    suspend fun openTab(url: String? = null): PageState

    /** 切到某个窗口；id 不存在时什么也不做。 */
    suspend fun selectTab(id: Long)

    /**
     * 关掉某个窗口。
     *
     * 关的是当前窗口时，焦点落到它**右边**的窗口上（与桌面浏览器一致）；右边没有就落到最后一个。
     * 最后一个窗口关掉之后不会自动补一个——工具下一次 [open] 会自己开。
     */
    suspend fun closeTab(id: Long)

    // ------------------------------------------------------------------ 界面

    /**
     * 唯一的 WebView 容器。
     *
     * 里面装的是**所有窗口的 WebView**，只有当前窗口可见。界面把它塞进自己的布局
     * （Compose 里是 `AndroidView`），引擎就从屏幕外搬到眼前；同一个 View 只能有一个父容器，
     * 所以取用时会先把它从屏幕外的宿主上摘下来。必须在主线程调用。
     *
     * 没有界面时这同一个容器挂在 Activity 装饰视图的屏幕外左侧——它既不可见，
     * 又是**真实渲染**的（`alpha=0` 的 View 不参与绘制，`draw(Canvas)` 出来是一张空白）。
     */
    fun surface(): View

    /**
     * 界面接管：从这里开始页面落在眼前，同时接收状态变化与网页要人回答的请求。
     *
     * 与 [surface] 的分工：[surface] 给"画在哪"，这个给"变了我怎么知道、弹窗谁来答"。
     */
    fun attachUi(ui: BrowserUi)

    /**
     * 界面离开：**只放下视图，不销毁任何东西**。
     *
     * 窗口、页面、JS 上下文、滚动位置全部保留；容器被放回屏幕外的宿主上继续渲染，
     * agent 的下一步操作仍然作用在同一页上。用户在应用里切到别的页面再回来，
     * 看到的是原来那一页——不是重开。
     */
    fun releaseUi()

    // ------------------------------------------------------------------ 当前窗口

    /** 当前页面的状态，不触发加载。 */
    suspend fun state(): PageState

    /** 可读正文（去标签、压缩空白），用于"这页说了什么"。 */
    suspend fun readable(maxChars: Int = 20_000): String

    /** 原始可见文本（含换行结构），用于需要精确定位的场合。 */
    suspend fun text(maxChars: Int = 20_000): String

    /** 页面上可点/可输入的元素，按可交互性排序并编号。 */
    suspend fun elements(limit: Int = 60): List<PageElement>

    /** 点第 [index] 个元素（下标来自最近一次 [elements]）。 */
    suspend fun click(index: Int): PageState

    /** 往第 [index] 个输入框写文本；[submit] 为真时随后回车提交。 */
    suspend fun type(index: Int, text: String, submit: Boolean): PageState

    /** 滚动：`up` / `down` 各滚一屏的 [factor] 倍（0.1–5）。 */
    suspend fun scroll(direction: String, factor: Double): PageState

    /** 截图，返回 JPEG 的 base64；页面没渲染出来时返回 null。 */
    suspend fun screenshot(): String?

    suspend fun back(): PageState

    suspend fun forward(): PageState

    suspend fun reload(): PageState

    /** 等某个 CSS 选择器出现；超时返回 false（不抛，交给工具说人话）。 */
    suspend fun waitFor(selector: String, timeoutMs: Long): Boolean

    /** 界面上的导航按钮：与 [open] 同一条路，只是不等加载完成。 */
    suspend fun navigateInPlace(url: String)

    companion object {
        val Key = ServiceKey<BrowserService>("host/browser")
    }
}

/** 当前页面的身份。 */
data class PageState(
    val url: String,
    val title: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** 加载失败的原始原因；成功时为 null。 */
    val error: String? = null,
    /** 这次操作发生在哪个窗口。0 表示还没有窗口。 */
    val tabId: Long = 0L,
)

/** 一个窗口（标签页）的状态。 */
data class BrowserTabState(
    val id: Long,
    val url: String,
    val title: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val loading: Boolean,
    /** 主框架真实加载进度，0..100；0 表示没有在加载。 */
    val progress: Int,
    /** 最近一次失败的原因；成功导航或重试后清空。 */
    val error: String? = null,
) {
    /** 一眼能认出这是哪一页：标题 > 主机名 > "新标签"。 */
    val label: String
        get() {
            title.takeIf { it.isNotBlank() }?.let { return it }
            url.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { java.net.URI(raw).host }.getOrNull()
                    ?.removePrefix("www.")
                    ?.let { return it }
            }
            return "新标签"
        }
}

/** 所有窗口的状态快照。 */
data class BrowserSnapshot(
    /** 当前窗口的 id；0 表示还没有任何窗口。 */
    val activeId: Long,
    /** 窗口数上限。界面靠它决定那个 `+` 还点不点得动。 */
    val maxTabs: Int,
    val tabs: List<BrowserTabState>,
) {
    val active: BrowserTabState? get() = tabs.firstOrNull { it.id == activeId }

    companion object {
        val Empty = BrowserSnapshot(activeId = 0L, maxTabs = 0, tabs = emptyList())
    }
}

/**
 * 界面接管时收到的回调。
 *
 * 只有**引擎自己做不到**的四件事在这里：选文件、要系统权限、全屏、以及状态变化。
 * 其余（弹窗、下载、外链、证书文案）全在宿主里做完——它们不需要界面，也不该因为
 * 界面没打开而失效。
 */
interface BrowserUi {

    /** 窗口状态变了（开始加载、进度、标题、历史、失败）。主线程调用。 */
    fun onSnapshot(snapshot: BrowserSnapshot)

    /**
     * 网页点了 `<input type=file>`。
     *
     * 实现方要：先把上一次没结掉的回调以 `null` 结掉（WebView 同一时刻只允许一个文件选择
     * 请求，漏回一次这个页面以后再也弹不出选择器）→ 存下这次的回调 → 拉起选择器 →
     * 选择器回来后用 `FileChooserParams.parseResult` 回填（取消时它给 `null`，正是 WebView
     * 要求的"用户没选"）。
     *
     * @return 是否真的把选择器拉起来了。`true` 表示"我接了，稍后给结果"；`false` 表示界面
     *   起不来——此时必须**已经**用 `onReceiveValue(null)` 结过这次请求。默认实现就是这么
     *   做的：当作用户取消，不让页面永远等。
     */
    fun requestFile(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        runCatching { callback.onReceiveValue(null) }
        return false
    }

    /**
     * `getUserMedia`（扫码、录音、视频通话）还缺 Android 运行时权限。
     *
     * 实现方要存下 [request] 并拉起系统权限对话框；结果回来后按 [androidPermissions] 的
     * 授予情况 `grant` / `deny`。**必须回答**，否则页面的 `getUserMedia` 永远 pending，
     * 用户看到的是"点了扫码没反应"。默认实现直接拒绝。
     */
    fun requestPermissions(request: PermissionRequest, androidPermissions: Array<String>) {
        runCatching { request.deny() }
    }

    /** 页面要全屏（视频）。同一时刻只允许一个自定义视图，实现方要先摘掉上一块。 */
    fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {}

    /** 页面**自己**结束全屏（视频播完）。不要回调 `onCustomViewHidden()`，会互相重入。 */
    fun hideFullscreen() {}
}

/**
 * 一个可交互元素。
 *
 * [index] 只在"最近一次 [BrowserService.elements]"的有效期内有意义；
 * [selector] 是为了日志与兜底（同一序号在别处复用时能看到它指向什么）。
 */
data class PageElement(
    val index: Int,
    val tag: String,
    /** `button` / `link` / `input` / `text` 这类粗分类，用来决定能不能输入。 */
    val kind: String,
    val text: String,
    val selector: String,
    /** 能不能往里输入文本。 */
    val typeable: Boolean,
    /** 输入框的 `type=password` 之类；非输入框为空。 */
    val inputType: String = "",
    /** 是否可见（被折叠或 `display:none` 的会被跳过，这里是保险）。 */
    val visible: Boolean = true,
)
