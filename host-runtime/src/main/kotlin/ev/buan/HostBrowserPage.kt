package ev.buan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.PageElement
import org.cordis.api.PageState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 当前窗口的**内容侧**操作：读正文、抽元素、点、输入、滚、截图、等选择器。
 *
 * 与 [HostBrowserSession] 分开，是因为那边已经在管生命周期、容器与窗口；
 * 这里只管"这一页里能做什么"。两者之间只隔一个约定：所有方法都在主线程上跑，
 * 且**永远作用于当前窗口**。
 */

// ---------------------------------------------------------------------- 读

/** [readable] 与 [text] 是同一个 JS 的两种口味：[readable] 先找出正文容器再压空白。 */
internal suspend fun HostBrowserSession.readPage(maxChars: Int, readable: Boolean): String =
    withContext(Dispatchers.Main) {
        val js = if (readable) {
            """
            (function(){
              var root = document.querySelector('main,article,[role=main]') || document.body;
              var t = (root && root.innerText) ? root.innerText : '';
              t = t.replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();
              return t;
            })()
            """.trimIndent()
        } else {
            "(function(){return document.body ? document.body.innerText : ''})()"
        }
        (evaluate(activeOnMain().web ?: return@withContext "", js) ?: "")
            .take(maxChars.coerceIn(500, 40_000))
    }

/**
 * 抽可交互元素，并按出现顺序编号。
 *
 * 编号是**这一轮**的坐标：抽的时候顺手给节点打上 `data-cordis-idx`，之后 [clickElement] /
 * [typeIntoElement] 直接用这个属性找人。页面的 DOM 一变，属性就跟着节点消失——这正是我们要的，
 * 比让模型自己拼 CSS 选择器稳。
 */
internal suspend fun HostBrowserSession.collectElements(limit: Int): List<PageElement> =
    withContext(Dispatchers.Main) {
        val web = activeOnMain().web ?: return@withContext emptyList()
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
        val array = runCatching { JSONArray(evaluate(web, js).orEmpty()) }.getOrNull()
            ?: return@withContext emptyList()
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

// ---------------------------------------------------------------------- 点

/**
 * 点第 [index] 个元素。
 *
 * 链接**不用 `el.click()`**：那个函数对 `target=_blank` 会在一个新窗口里打开，而会话里
 * 如果只有当前这一页，用户看到的就是"点了没反应"。
 *
 *  - `_blank` → 交给引擎开一个新窗口（这就是多窗口真正的用处）；
 *  - 普通链接 → `location.href`，保证留在同一页里；
 *  - 其它元素 → 照常 `click()`，让页面自己的处理逻辑跑。
 */
internal suspend fun HostBrowserSession.clickElement(index: Int): PageState =
    withContext(Dispatchers.Main) {
        val tab = activeOnMain()
        val web = tab.web ?: return@withContext PageState("", "", false, false, "窗口不可用", tab.id)
        val js = """
            (function(){
              var el = document.querySelector('[data-cordis-idx="$index"]');
              if (!el) return 'missing';
              el.scrollIntoView({block:'center'});
              var tag = el.tagName.toLowerCase();
              if (tag === 'a' && el.href) {
                if ((el.getAttribute('target') || '').toLowerCase() === '_blank') {
                  return 'blank:' + el.href;
                }
                window.location.href = el.href;
                return 'ok';
              }
              el.click();
              return 'ok';
            })()
        """.trimIndent()
        val result = evaluate(web, js)
        if (result == "missing") {
            return@withContext PageState(
                "", "", false, false,
                "元素 $index 已失效（页面变了，请重新 browser_find）", tab.id,
            )
        }
        if (result != null && result.startsWith(NEW_TAB_PREFIX)) {
            val url = result.removePrefix(NEW_TAB_PREFIX)
            delay(SETTLE_DELAY_MS)
            val snapshot = snapshot()
            return@withContext if (snapshot.tabs.size < snapshot.maxTabs) {
                openTab(url)
            } else {
                navigateOnMain(tab, url) { it.loadUrl(url) }
            }
        }
        // 点完常常触发导航：给它一点时间落定，再报状态。
        delay(SETTLE_DELAY_MS)
        stateNow(tab)
    }

/**
 * 往第 [index] 个输入框写文本。
 *
 * 走的必须是**原生 setter**：React/Vue 的受控组件监听 `input` 事件，而这些事件只认
 * `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set` 那一次调用。
 * 直接 `el.value = x` 它们的 state 不会更新，回车一提交就是空表单。
 */
internal suspend fun HostBrowserSession.typeIntoElement(
    index: Int,
    text: String,
    submit: Boolean,
): PageState = withContext(Dispatchers.Main) {
    val tab = activeOnMain()
    val web = tab.web ?: return@withContext PageState("", "", false, false, "窗口不可用", tab.id)
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
    val result = evaluate(web, js)
    if (result == "missing") {
        return@withContext PageState(
            "", "", false, false,
            "元素 $index 已失效（页面变了，请重新 browser_find）", tab.id,
        )
    }
    if (submit) delay(SETTLE_DELAY_MS * 2)
    stateNow(tab)
}

/** 滚动：`up` / `down` 各滚一屏的 [factor] 倍。 */
internal suspend fun HostBrowserSession.scrollPage(direction: String, factor: Double): PageState =
    withContext(Dispatchers.Main) {
        val tab = activeOnMain()
        val web = tab.web ?: return@withContext PageState("", "", false, false, "窗口不可用", tab.id)
        val sign = if (direction.equals("up", ignoreCase = true)) -1 else 1
        val ratio = factor.coerceIn(0.1, 5.0)
        evaluate(web, "(function(){window.scrollBy(0, $sign * window.innerHeight * $ratio);return 'ok'})()")
        delay(300)
        stateNow(tab)
    }

// ---------------------------------------------------------------------- 截图

/**
 * 截当前窗口。
 *
 * 容器挂在屏幕外时它的尺寸是屏幕大小（`translationX` 挪出去了，不是在布局里缩成 0），
 * 所以 `draw(Canvas)` 拿到的是一张真实渲染的图。界面接管时尺寸就是界面尺寸——
 * 截图与用户看到的一致，这正是合并两套 WebView 的额外好处。
 */
internal suspend fun HostBrowserSession.capturePage(): String? = withContext(Dispatchers.Main) {
    val web = activeOnMain().web ?: return@withContext null
    if (web.width <= 1 || web.height <= 1) {
        val width = 1080
        val height = 2400
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        web.layout(0, 0, width, height)
    }
    val bitmap = runCatching {
        Bitmap.createBitmap(
            web.width.coerceAtLeast(1),
            web.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    }.getOrNull() ?: return@withContext null
    runCatching { web.draw(Canvas(bitmap)) }.onFailure { return@withContext null }
    val bytes = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        stream.toByteArray()
    }
    bitmap.recycle()
    Base64.getEncoder().encodeToString(bytes)
}

// ---------------------------------------------------------------------- 等

/** 等某个 CSS 选择器出现；超时返回 false，不抛。 */
internal suspend fun HostBrowserSession.awaitSelector(selector: String, timeoutMs: Long): Boolean =
    withContext(Dispatchers.Main) {
        val web = activeOnMain().web ?: return@withContext false
        val escaped = JSONObject.quote(selector)
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500, 30_000)
        while (System.currentTimeMillis() < deadline) {
            val found = evaluate(web, "(function(){return document.querySelector($escaped) ? '1' : '0'})()")
            if (found == "1") return@withContext true
            delay(200)
        }
        false
    }

// ---------------------------------------------------------------------- 求值

/**
 * 在主线程上求值一段 JS。
 *
 * `evaluateJavascript` 的回调给的是**再编码一层的 JSON 字面量**：JS 返回字符串时，
 * 拿到的先是 `"\"..."`。所以要先 JSONTokener 解一层，才是页面里的原文。
 */
internal suspend fun HostBrowserSession.evaluate(web: WebView, js: String): String? =
    withContext(Dispatchers.Main) {
        val result = kotlinx.coroutines.CompletableDeferred<String?>()
        runCatching {
            web.evaluateJavascript(js) { raw ->
                val unwrapped = runCatching { JSONTokener(raw ?: "null").nextValue() as? String }
                    .getOrNull()
                result.complete(unwrapped)
            }
        }.onFailure { result.complete(null) }
        withTimeoutOrNull(EVAL_TIMEOUT_MS) { result.await() }
    }

private const val NEW_TAB_PREFIX = "blank:"
private const val EVAL_TIMEOUT_MS = 10_000L
private const val SETTLE_DELAY_MS = 500L
