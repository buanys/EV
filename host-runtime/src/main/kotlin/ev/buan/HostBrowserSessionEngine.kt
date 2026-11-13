package ev.buan

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * WebView 回调落进会话的那一端：加载开始/结束、标题、进度、历史、失败，以及两件只有界面
 * 做得到的事（选文件、运行时权限）。
 *
 * 为什么要单独一个文件：[BrowserEngine] 装的是"什么时候回调"，这里装的是"回调到了之后
 * 状态怎么变"。两边分开之后，加一个回调只会在其中一个文件里出现一行。
 *
 * 所有函数都假定在**主线程**被调用——WebView 的回调都在主线程。
 */

internal fun HostBrowserSession.onTabStarted(tab: BrowserTab, url: String?) {
    if (!url.isNullOrBlank() && !url.startsWith("about:")) tab.url = url
    tab.loading = true
    tab.error = null
    tab.progress = 0
    onChanged()
}

internal fun HostBrowserSession.onTabFinished(tab: BrowserTab, url: String?, title: String?) {
    tab.loading = false
    tab.progress = 100
    if (!url.isNullOrBlank()) {
        tab.url = if (url.startsWith("about:blank")) "" else url
    }
    if (!title.isNullOrBlank()) tab.title = title
    refreshControls(tab)
    // 先交卷，再让等的那一方自己读一次状态（它读的是页面里的真值）。
    tab.pending?.complete(Unit)
    onChanged()
}

internal fun HostBrowserSession.onTabTitle(tab: BrowserTab, title: String?) {
    if (!title.isNullOrBlank() && title != tab.title) {
        tab.title = title
        onChanged()
    }
}

internal fun HostBrowserSession.onTabProgress(tab: BrowserTab, value: Int) {
    val next = value.coerceIn(0, 100)
    tab.progress = next
    if (next >= 100) tab.loading = false
    onChanged()
}

internal fun HostBrowserSession.onTabHistoryChanged(tab: BrowserTab) {
    refreshControls(tab)
    onChanged()
}

internal fun HostBrowserSession.onTabFailed(tab: BrowserTab, message: String) {
    tab.loading = false
    tab.error = message
    tab.pending?.complete(Unit)
    onChanged()
}

/**
 * 网页点了 `<input type=file>`。
 *
 * 界面没接管时必须**自己**把这次请求以 `null` 结掉：WebView 同一时刻只允许一个文件选择
 * 请求，漏回一次，这个页面以后再也弹不出选择器。
 */
internal fun HostBrowserSession.requestFileFromWeb(
    callback: ValueCallback<Array<Uri>>,
    params: WebChromeClient.FileChooserParams,
): Boolean {
    val current = browserUi ?: run {
        runCatching { callback.onReceiveValue(null) }
        return false
    }
    return runCatching { current.requestFile(callback, params) }.getOrElse {
        runCatching { callback.onReceiveValue(null) }
        false
    }
}

/** 网页要摄像头/麦克风，但 Android 运行时权限还没给。界面不在就拒绝，不让它永远挂着。 */
internal fun HostBrowserSession.requestPermissionsFromWeb(
    request: PermissionRequest,
    missing: Array<String>,
): Boolean {
    val current = browserUi ?: return false
    runCatching { current.requestPermissions(request, missing) }
    return true
}
