package org.cordis.api

import org.cordis.ServiceKey

/**
 * 离屏浏览器：把页面渲染出来并返回正文。
 *
 * 为什么需要这一层：插件能自己发 HTTP 拿静态 HTML，但**靠 JS 渲染的页面**抓回来是个空壳。
 * WebView 必须活在主线程、必须有一个 Android `Context`，而插件两样都拿不到——
 * 所以渲染这件事只能由宿主做，插件通过这个契约要结果。
 *
 * 调用方应当**先尝试普通 HTTP**，只在正文明显是空壳时才走这里：
 * 一次渲染要起 WebView、跑 JS、等加载，比一次 GET 贵两个数量级。
 */
interface PageReader {

    /**
     * 渲染 [url] 并返回正文。
     *
     * [timeoutMs] 到点即放弃并返回当前已有的内容（哪怕是空的），不抛异常——
     * 一次工具调用失败不该掀翻整个对话回合，调用方需要拿到"没抓到"这个结果本身。
     */
    suspend fun render(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RenderedPage

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        val Key = ServiceKey<PageReader>("host/page-reader")
    }
}

/**
 * 一次渲染的结果。
 *
 * [text] 是页面可见文本（`document.body.innerText` 的去噪结果），不是 HTML——
 * 把这个直接喂给模型比喂标签更省 token，也更不容易被模型当成指令。
 */
data class RenderedPage(
    val url: String,
    val title: String,
    val text: String,
    /** 内容超出上限被截断。调用方应当据此告诉模型"还有后续"。 */
    val truncated: Boolean = false,
    /** 渲染失败的原因；为空表示成功。失败时 [text] 可能仍有部分内容。 */
    val error: String? = null,
)
