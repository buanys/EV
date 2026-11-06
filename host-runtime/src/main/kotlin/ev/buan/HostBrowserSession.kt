package ev.buan

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.BrowserService
import org.cordis.api.PageElement
import org.cordis.api.PageState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 内置浏览器的会话实现：**一个常驻的 WebView**，而不是每次新建。
 *
 * 与 [HostPageReader] 的分工很明确：那个是"取一次正文就走"，每次新建再销毁，快；
 * 这个是"连续操作一页"，要保留 cookie、DOM 与历史，因此必须常驻。两者共用同一套
 * attach 技巧（不挂在窗口树里的 WebView 在部分 ROM 上不跑 JS）。
 *
 * 三处刻意的设计：
 *
 *  1. **元素用下标寻址**：抽元素时给节点打上 `data-cordis-idx`，之后按下标点。页面的 DOM
 *     一变，旧下标立刻失效——这比让模型拼 CSS 选择器安全得多。
 *  2. **输入走原生 setter**：React/Vue 的受控组件监听的是原生 setter 触发的 input 事件，
 *     直接改 `el.value` 它们的 state 不会更新，回车一提交就是空表单。
 *  3. **截图前先量一次**：会话挂在 1×1 的不可见表单里，直接 draw 只能拿到 1 个像素；
 *     截图时把它量成 1080×2400 再画。
 */
internal class HostBrowserSession(
    private val context: Context,
    private val activityProvider: () -> Activity?,
) : BrowserService {

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var container: FrameLayout? = null
    private var pending: CompletableDeferred<Unit>? = null

    /** 最后一次打开的网址：会话被内存压力回收后，靠它把页面加载回来。 */
    private var lastUrl: String? = null

    // ---------------------------------------------------------------- 生命周期

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        view?.let { return it }
        val created = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false

                override fun onPageFinished(v: WebView, url: String) {
                    // 只认第一次：重定向与 iframe 都会重复触发，早交卷等于读到一半。
                    pending?.complete(Unit)
                }
            }
        }
        activityProvider()?.let { activity ->
            // 挂在屏幕**左侧外面**而不是 alpha=0 的 1×1 容器里：
            //  alpha=0 的 View 不参与绘制，`draw(Canvas)` 出来的截图是一张空白；
            //  1×1 的尺寸则让点击命中与滚动都没有意义。放到画布外既不可见又是真实渲染。
            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(720)
            val height = metrics.heightPixels.coerceAtLeast(1280)
            val host = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(width, height)
                translationX = -(width + 400f)
            }
            runCatching {
                (activity.window?.decorView as? ViewGroup)?.addView(host)
                host.addView(created, FrameLayout.LayoutParams(width, height))
                container = host
            }
        }
        view = created
        return created
    }

    /** 销毁常驻 WebView；内存压力或插件停用时调用。 */
    fun close() {
        main.post {
            pending?.cancel()
            pending = null
            container?.let { host ->
                (host.parent as? ViewGroup)?.removeView(host)
                host.removeAllViews()
            }
            runCatching {
                view?.stopLoading()
                view?.destroy()
            }
            view = null
            container = null
        }
    }

    // ---------------------------------------------------------------- 导航

    override suspend fun open(url: String): PageState = navigate(url) {
        lastUrl = url
        it.loadUrl(url)
    }

    /**
     * 拿一个**已经加载好页面**的 WebView。
     *
     * 会话会在系统内存压力下被 `close()`：下次再用时 WebView 是新的、页面是空的。
     * 这里自动把上一页加载回来，而不是让工具层返回一堆空字符串——那看起来像"页面没有内容"，
     * 而真相是"我们把它弄丢了"。
     */
    private suspend fun ready(): WebView {
        val web = ensureView()
        val current = web.url
        val blank = current.isNullOrEmpty() || current == "about:blank"
        val target = lastUrl
        if (blank && target != null) {
            navigate(target) { it.loadUrl(target) }
            return ensureView()
        }
        return web
    }

    private suspend fun navigate(target: String?, action: (WebView) -> Unit): PageState =
        withContext(Dispatchers.Main) {
            val web = ensureView()
            val done = CompletableDeferred<Unit>()
            pending = done
            runCatching { action(web) }.onFailure { error ->
                pending = null
                return@withContext PageState(target.orEmpty(), "", false, false, "导航失败：${error.message}")
            }
            withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
            pending = null
            // SPA 在 onPageFinished 之后还有一轮渲染，等一拍再读状态。
            delay(SETTLE_DELAY_MS)
            stateNow(web)
        }

    override suspend fun state(): PageState = withContext(Dispatchers.Main) { stateNow(ready()) }

    private suspend fun stateNow(web: WebView): PageState {
        val payload = evaluate(web, """(function(){return JSON.stringify({url:location.href,title:document.title||''})})()""")
        val json = runCatching { JSONObject(payload.orEmpty()) }.getOrNull()
        return PageState(
            url = json?.optString("url").orEmpty().ifEmpty { web.url.orEmpty() },
            title = json?.optString("title").orEmpty(),
            canGoBack = web.canGoBack(),
            canGoForward = web.canGoForward(),
        )
    }

    override suspend fun back(): PageState = historyStep(-1)

    override suspend fun forward(): PageState = historyStep(1)

    /**
     * 前进/后退走页面自己的 `history`，不走 WebView 的 `goBack()`。
     *
     * 原因：点击链接是我们用 `location.href = ...` 驱动的，那一步在页面 history 里一定有一条；
     * 而 WebView 的 `canGoBack()` 在这条会话上始终是 false（实测），用它做判断会永远"没有可退回的页面"。
     */
    private suspend fun historyStep(delta: Int): PageState = withContext(Dispatchers.Main) {
        val web = ensureView()
        val before = evaluate(web, "(function(){return String(history.length)})()")
        val length = before?.toIntOrNull() ?: 1
        if (length <= 1) return@withContext stateNow(web)
        val done = CompletableDeferred<Unit>()
        pending = done
        evaluate(web, "(function(){history.go($delta);return 'ok'})()")
        withTimeoutOrNull(NAV_TIMEOUT_MS) { done.await() }
        pending = null
        delay(SETTLE_DELAY_MS)
        stateNow(web)
    }

    override suspend fun reload(): PageState = navigate(null) { it.reload() }

    override suspend fun waitFor(selector: String, timeoutMs: Long): Boolean =
        withContext(Dispatchers.Main) {
            val web = ensureView()
            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500, 30_000)
            val escaped = JSONObject.quote(selector)
            while (System.currentTimeMillis() < deadline) {
                val found = evaluate(web, "(function(){return document.querySelector($escaped) ? '1' : '0'})()")
                if (found == "1") return@withContext true
                delay(200)
            }
            false
        }

    // ---------------------------------------------------------------- 内容

    override suspend fun readable(maxChars: Int): String = withContext(Dispatchers.Main) {
        val js = """
            (function(){
              var root = document.querySelector('main,article,[role=main]') || document.body;
              var t = (root && root.innerText) ? root.innerText : '';
              t = t.replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();
              return t;
            })()
        """.trimIndent()
        (evaluate(ready(), js) ?: "").take(maxChars.coerceIn(500, 40_000))
    }

    override suspend fun text(maxChars: Int): String = withContext(Dispatchers.Main) {
        val js = "(function(){return document.body ? document.body.innerText : ''})()"
        (evaluate(ready(), js) ?: "").take(maxChars.coerceIn(500, 40_000))
    }

    override suspend fun elements(limit: Int): List<PageElement> = withContext(Dispatchers.Main) {
        val cap = limit.coerceIn(1, 120)
        val js = """
            (function(){
              var nodes = document.querySelectorAll('a,button,input,textarea,select,[role=button],[onclick],[contenteditable=true]');
              var out = [];
              for (var i = 0; i < nodes.length && out.length < $cap; i++) {
                var el = nodes[i];
                if (el.disabled) continue;
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                if (!rect || rect.width < 2 || rect.height < 2) continue;
                if (style.visibility === 'hidden' || style.display === 'none') continue;
                var idx = out.length;
                el.setAttribute('data-cordis-idx', String(idx));
                var tag = el.tagName.toLowerCase();
                var role = (el.getAttribute('role') || '').toLowerCase();
                var typeable = tag === 'input' || tag === 'textarea' || el.isContentEditable;
                var kind = tag === 'a' ? 'link'
                         : (tag === 'button' || role === 'button') ? 'button'
                         : typeable ? 'input' : 'other';
                var label = (el.innerText || el.value || el.getAttribute('aria-label')
                             || el.getAttribute('placeholder') || el.getAttribute('title') || '').trim();
                out.push({
                  index: idx, tag: tag, kind: kind, text: label.substring(0, 120),
                  typeable: typeable, inputType: (el.getAttribute('type') || ''),
                  selector: '[data-cordis-idx="' + idx + '"]', visible: true
                });
              }
              return JSON.stringify(out);
            })()
        """.trimIndent()
        val payload = evaluate(ready(), js).orEmpty()
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return@withContext emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    PageElement(
                        index = item.optInt("index"),
                        tag = item.optString("tag"),
                        kind = item.optString("kind"),
                        text = item.optString("text"),
                        selector = item.optString("selector"),
                        typeable = item.optBoolean("typeable"),
                        inputType = item.optString("inputType"),
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 操作

    override suspend fun click(index: Int): PageState = withContext(Dispatchers.Main) {
        val js = """
            (function(){
              var el = document.querySelector('[data-cordis-idx="$index"]');
              if (!el) return 'missing';
              el.scrollIntoView({block:'center'});
              // 链接直接改 location：el.click() 对 target=_blank 会在一个新窗口里打开，
              // 而会话里只有一个 WebView，用户看到的就是"点了没反应"。
              var tag = el.tagName.toLowerCase();
              if (tag === 'a' && el.href) { window.location.href = el.href; return 'ok'; }
              el.click();
              return 'ok';
            })()
        """.trimIndent()
        val result = evaluate(ensureView(), js)
        if (result == "missing") {
            return@withContext PageState("", "", false, false, "元素 $index 已失效（页面变了，请重新 find_elements）")
        }
        // 点完常常触发导航：给它一点时间落定，再报状态。
        delay(SETTLE_DELAY_MS)
        stateNow(ensureView())
    }

    override suspend fun type(index: Int, text: String, submit: Boolean): PageState =
        withContext(Dispatchers.Main) {
            val payload = JSONObject.quote(text)
            val js = """
                (function(){
                  var el = document.querySelector('[data-cordis-idx="$index"]');
                  if (!el) return 'missing';
                  el.focus();
                  if (el.isContentEditable) { el.innerText = $payload; }
                  else {
                    var proto = el.tagName.toLowerCase() === 'textarea'
                      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    if (setter) setter.call(el, $payload); else el.value = $payload;
                  }
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                  if ($submit) {
                    el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, which:13, bubbles:true}));
                    if (el.form && el.form.requestSubmit) { try { el.form.requestSubmit(); } catch (e) {} }
                  }
                  return 'ok';
                })()
            """.trimIndent()
            val result = evaluate(ensureView(), js)
            if (result == "missing") {
                return@withContext PageState("", "", false, false, "元素 $index 已失效（页面变了，请重新 find_elements）")
            }
            if (submit) delay(SETTLE_DELAY_MS * 2)
            stateNow(ensureView())
        }

    override suspend fun scroll(direction: String, factor: Double): PageState =
        withContext(Dispatchers.Main) {
            val sign = if (direction.equals("up", ignoreCase = true)) -1 else 1
            val ratio = factor.coerceIn(0.1, 5.0)
            val js = "(function(){window.scrollBy(0, $sign * window.innerHeight * $ratio);return 'ok'})()"
            evaluate(ensureView(), js)
            delay(300)
            stateNow(ensureView())
        }

    override suspend fun screenshot(): String? = withContext(Dispatchers.Main) {
        val web = ensureView()
        if (web.width <= 1) {
            // 没有 Activity 可挂时退化成这个分支：按手机比例量一次，否则只能拿到一个像素。
            val width = 1080
            val height = 2400
            web.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            web.layout(0, 0, width, height)
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(web.width.coerceAtLeast(1), web.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return@withContext null
        runCatching { web.draw(Canvas(bitmap)) }.onFailure { return@withContext null }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        Base64.getEncoder().encodeToString(bytes)
    }

    // ---------------------------------------------------------------- 求值

    /**
     * 在主线程上求值一段 JS。
     *
     * `evaluateJavascript` 的回调给的是**再编码一层的 JSON 字面量**：JS 返回字符串时，
     * 拿到的先是 `"\"...\""`。所以要先 JSONTokener 解一层，才是页面里的原文。
     */
    private suspend fun evaluate(web: WebView, js: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        runCatching {
            web.evaluateJavascript(js) { raw ->
                val unwrapped = runCatching { JSONTokener(raw ?: "null").nextValue() as? String }.getOrNull()
                result.complete(unwrapped)
            }
        }.onFailure { result.complete(null) }
        withTimeoutOrNull(EVAL_TIMEOUT_MS) { result.await() }
    }

    private companion object {
        const val NAV_TIMEOUT_MS = 20_000L
        const val EVAL_TIMEOUT_MS = 10_000L
        const val SETTLE_DELAY_MS = 500L
    }
}
