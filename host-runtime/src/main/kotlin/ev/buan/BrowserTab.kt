package ev.buan

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import org.cordis.api.BrowserTabState

/**
 * 一个窗口的全部可变状态。
 *
 * 一个窗口 = 一个 WebView。字段在这里**缓存** WebView 的读法，原因有两个：
 *
 *  1. `canGoBack()` 这类查询只能主线程调，而工具与界面的快照可能来自别的线程；
 *  2. 快照每几百毫秒就可能被要一次（界面画标签条），每次都往 WebView 里问一遍
 *     比读一个 Boolean 贵得多。
 *
 * 缓存由 [HostBrowserSession] 在三个时机刷新：导航开始、页面结束、历史变化。
 */
internal class BrowserTab(val id: Long) {

    /** 这个窗口的 WebView；只有销毁时才置空。 */
    var web: WebView? = null

    /** 当前地址；`about:blank` 归一成空串——那是"还没打开页面"，不是"打开了一个叫 about:blank 的页面"。 */
    var url: String = ""

    var title: String = ""
    var loading: Boolean = false
    var progress: Int = 0

    /** 最近一次失败的原因；下一次导航开始时清空。 */
    var error: String? = null

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false

    /** 正在等 `onPageFinished` 的那次导航。同一时刻一个窗口只有一次导航在等。 */
    var pending: CompletableDeferred<Unit>? = null

    fun state(): BrowserTabState = BrowserTabState(
        id = id,
        url = url,
        title = title,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        loading = loading,
        progress = progress,
        error = error,
    )
}
