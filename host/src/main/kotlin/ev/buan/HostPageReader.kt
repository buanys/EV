package ev.buan

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.PageReader
import org.cordis.api.RenderedPage
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离屏浏览器：起一个不可见的 WebView，把页面跑完 JS，取回正文。
 *
 * 为什么必须由宿主做：WebView 只能在主线程创建，且需要一个 Android `Context`；
 * 插件两样都没有。插件先试普通 HTTP，抓到空壳才转到这里——一次渲染比一次 GET 贵两个数量级。
 *
 * 三个容易踩的点，都在实现里处理了：
 *
 * 1. **attach**：不 attach 到窗口树的 WebView 在部分 ROM 上不跑 JS，`evaluateJavascript`
 *    会回调 null。有 Activity 时把它挂到一个 1×1 的不可见容器上；没有 Activity 时照常加载
 *    （多数设备仍可用），失败会如实返回错误而不是假装成功。
 * 2. **onPageFinished 会重复触发**（重定向、iframe）。用 [started] 兜住，只取第一次。
 * 3. **必须 destroy**。WebView 持有原生资源，漏一个就漏几 MB，而且它的 JS 定时器会继续跑。
 *    超时路径也要走同一段清理。
 */
class HostPageReader(
    private val context: Context,
    private val activityProvider: () -> Activity?,
) : PageReader {

    private val main = Handler(Looper.getMainLooper())

    override suspend fun render(url: String, timeoutMs: Long, selector: String?): RenderedPage = withContext(Dispatchers.Main) {
        val outcome = CompletableDeferred<RenderedPage>()
        val started = AtomicBoolean(false)
        var container: FrameLayout? = null
        var view: WebView? = null

        fun finish(page: RenderedPage) {
            if (outcome.isCompleted) return
            // 清理放在 finish 里而不是 finally：超时和其它异常路径都从这里出去，
            // 只写一份清理代码，少一种漏 destroy 的写法。
            runCatching {
                container?.let { host ->
                    (host.parent as? ViewGroup)?.removeView(host)
                    host.removeAllViews()
                }
                view?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.stopLoading()
                    it.destroy()
                }
            }
            outcome.complete(page)
        }

        try {
            @SuppressLint("SetJavaScriptEnabled")
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false // 抓正文不需要图，省流量也省时间
                settings.blockNetworkImage = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false

                    override fun onPageFinished(v: WebView, loadedUrl: String) {
                        if (!started.compareAndSet(false, true)) return
                        // SPA 在 onPageFinished 之后还会再渲染一轮；给它一点时间再取。
                        main.postDelayed({ extract(v, loadedUrl, selector, ::finish) }, SETTLE_DELAY_MS)
                    }

                    override fun onPageStarted(v: WebView, startedUrl: String, icon: Bitmap?) = Unit
                }
            }
            view = webView

            val activity = activityProvider()
            if (activity != null) {
                container = FrameLayout(activity).apply {
                    // 不可见但仍在窗口树里：visibility=GONE 会让它不参与渲染，JS 可能不跑。
                    alpha = 0f
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                }
                runCatching {
                    activity.window?.decorView?.let { decor ->
                        (decor as? ViewGroup)?.addView(container)
                        container.addView(webView, FrameLayout.LayoutParams(1, 1))
                    }
                }
            }

            webView.loadUrl(url)

            val result = withTimeoutOrNull(timeoutMs) { outcome.await() }
                ?: RenderedPage(
                    url = url,
                    title = "",
                    text = "",
                    error = "渲染超时（${timeoutMs}ms）：页面可能仍在加载或需要登录",
                ).also { finish(it) }

            result
        } catch (t: Throwable) {
            finish(
                RenderedPage(
                    url = url,
                    title = "",
                    text = "",
                    error = "渲染失败：${t.message ?: t.javaClass.simpleName}",
                ),
            )
            outcome.await()
        }
    }

    /** 取出可见文本。用 `innerText` 而不是 `innerHTML`：模型要看的是内容，不是标签。 */
    private fun extract(view: WebView, url: String, selector: String?, finish: (RenderedPage) -> Unit) {
        // 选择器用 JSON 编码后再拼进 JS：直接字符串拼接会被引号打断，也给了页面注入的机会。
        val selectorJson = org.json.JSONObject.quote(selector ?: "")
        val js = """
            (function(){
              var sel = $selectorJson;
              var root = document.body;
              if (sel) {
                // 选择器可能匹配多个，取第一个有内容的：'#content_left, .result' 这类写法很常见。
                var nodes = document.querySelectorAll(sel);
                for (var i = 0; i < nodes.length; i++) {
                  if (nodes[i] && nodes[i].innerText && nodes[i].innerText.trim()) { root = nodes[i]; break; }
                }
              }
              var t = (root && root.innerText) ? root.innerText : '';
              t = t.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
              return JSON.stringify({ title: document.title || '', text: t });
            })()
        """.trimIndent()

        runCatching {
            view.evaluateJavascript(js) { raw ->
                // evaluateJavascript 回调收到的是「JSON 字符串字面量」——JS 返回字符串时，
                // WebView 会把它再编码一次。所以要先 JSONTokener 解一层，才是真正的 JSON 文本。
                val payload = runCatching {
                    JSONTokener(raw ?: "null").nextValue() as? String ?: ""
                }.getOrNull().orEmpty()

                val parsed = runCatching { JSONObject(payload) }.getOrNull()
                if (parsed == null) {
                    finish(
                        RenderedPage(
                            url = url,
                            title = "",
                            text = "",
                            error = "取正文失败：页面返回的内容无法解析",
                        ),
                    )
                    return@evaluateJavascript
                }

                val title = parsed.optString("title").orEmpty()
                val body = parsed.optString("text").orEmpty()
                if (body.isBlank()) {
                    finish(
                        RenderedPage(
                            url = url,
                            title = title,
                            text = "",
                            error = "页面渲染完成但取不到正文（可能需要登录、或被反爬拦截）",
                        ),
                    )
                } else {
                    val (text, truncated) = truncate(body)
                    finish(RenderedPage(url = url, title = title, text = text, truncated = truncated))
                }
            }
        }.onFailure {
            finish(RenderedPage(url, "", "", error = "取正文失败：${it.message ?: it.javaClass.simpleName}"))
        }
    }

    private fun truncate(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_TEXT_CHARS) return text to false
        return text.take(MAX_TEXT_CHARS) to true
    }

    private companion object {
        /** SPA 在 onPageFinished 后还有一轮渲染；等这么久再取，命中率明显更高。 */
        const val SETTLE_DELAY_MS = 700L
        /** 一页正文的上限。再长对模型也没有意义，只会挤掉对话上下文。 */
        const val MAX_TEXT_CHARS = 30_000
    }
}
