package org.cordis.api

import org.cordis.ServiceKey

/**
 * 内置浏览器的会话：打开、读、找元素、点、输入、滚动、截图、前进后退。
 *
 * 为什么由宿主提供：一个可交互的页面需要一个**持续存在**的 WebView（保留 cookie、DOM 与历史），
 * 而 WebView 只能在主线程创建、需要一个 `Context`——插件两样都没有。`PageReader` 那种
 * "每次新建一个、取完立刻销毁"的做法适合抓正文，不适合连续操作。
 *
 * 三条约定：
 *
 *  1. **元素用下标寻址**，不是选择器。`find_elements` 给出一张带序号的表（并同时给出选择器），
 *     `click` / `type` 用的是那个序号；页面的 DOM 变了，序号就作废，下一次 `find_elements` 重排。
 *     让模型自己拼 CSS 选择器是幻觉的高发区。
 *  2. **每次操作后都返回新的 [PageState]**，调用方不必再问一次"现在在哪"。
 *  3. **同一个会话只有一页**。多标签是界面的功能，不是 agent 的功能——要并行开两个页面，
 *     就把两个 URL 分开取。
 */
interface BrowserService {

    /** 打开一个网址（或 `about:blank` 重置），等页面加载完成再返回。 */
    suspend fun open(url: String): PageState

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

    /** 截图，返回 PNG 的 base64；页面没渲染出来时返回 null。 */
    suspend fun screenshot(): String?

    suspend fun back(): PageState

    suspend fun forward(): PageState

    suspend fun reload(): PageState

    /** 等某个 CSS 选择器出现；超时返回 false（不抛，交给工具说人话）。 */
    suspend fun waitFor(selector: String, timeoutMs: Long): Boolean

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
)

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
