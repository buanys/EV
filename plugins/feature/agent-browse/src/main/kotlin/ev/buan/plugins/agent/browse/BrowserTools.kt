package ev.buan.plugins.agent.browse

import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolResult
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.BrowserService
import org.cordis.api.PageElement
import org.cordis.api.PageState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置浏览器的十四个动作：打开、读、找、点、输、滚、截图、历史，以及窗口管理。
 *
 * 为什么这些不是"再多两个 web_ 工具"：`web_fetch` 是一次性的 GET+渲染，取完即走；
 * 这一组是**一次连续的会话**——登录、翻页、填表、提交，中间 cookie 与 DOM 都要留着。
 *
 * 两个刻意的取舍：
 *
 *  1. **危险标记只打在会改变结果的动作上**：`open` / `click` / `type` / `submit` 会以
 *     用户的身份操作网站（买、发、改），必须过审批；读正文、找元素、滚动、前进后退不打。
 *     全都打等于让每一步都点一次批准，agent 就用不起来了。
 *  2. **元素失败要说清"要重找"**：下标失效时返回的错误里直接写 `find_elements`，
 *     模型下一轮就知道该做什么，而不是重试同一个已经消失的序号。
 */
internal object BrowserTools {

    fun all(browser: BrowserService): List<Tool> = listOf(
        tool(OPEN) { args -> open(browser, args) },
        tool(STATE) { state(browser) },
        tool(READABLE) { args -> readable(browser, args) },
        tool(TEXT) { args -> text(browser, args) },
        tool(FIND) { args -> find(browser, args) },
        tool(CLICK) { args -> click(browser, args) },
        tool(TYPE) { args -> type(browser, args) },
        tool(SCROLL) { args -> scroll(browser, args) },
        ScreenshotTool(browser),
        tool(BACK) { state(browser.back()) },
        tool(FORWARD) { state(browser.forward()) },
        tool(RELOAD) { state(browser.reload()) },
        tool(WAIT) { args -> waitFor(browser, args) },
        tool(TABS) { args -> tabs(browser, args) },
    )

    // ---------------------------------------------------------------- 动作

    private suspend fun open(browser: BrowserService, args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isEmpty()) return invalid("url 必填（要带 http:// 或 https://）")
        return state(browser.open(normalize(url)))
    }

    private suspend fun state(browser: BrowserService, args: JSONObject = JSONObject()): String =
        state(browser.state())

    private fun state(page: PageState): String = JSONObject()
        .put("url", page.url)
        .put("title", page.title)
        .put("can_go_back", page.canGoBack)
        .put("can_go_forward", page.canGoForward)
        .put("error", page.error ?: JSONObject.NULL)
        .toString()

    private suspend fun readable(browser: BrowserService, args: JSONObject): String =
        JSONObject()
            .put("text", browser.readable(limit(args, 20_000)))
            .put("url", browser.state().url)
            .toString()

    private suspend fun text(browser: BrowserService, args: JSONObject): String =
        JSONObject().put("text", browser.text(limit(args, 20_000))).toString()

    private suspend fun find(browser: BrowserService, args: JSONObject): String {
        val elements = browser.elements(limit(args, 60))
        val array = JSONArray()
        elements.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("items", array)
            .put("count", elements.size)
            .put("hint", if (elements.isEmpty()) "页面没有可点元素：可能还在加载，或内容在 iframe 里" else null)
            .toString()
    }

    private suspend fun click(browser: BrowserService, args: JSONObject): String {
        val index = args.optInt("index", -1)
        if (index < 0) return invalid("index 必填（取自 find_elements 的编号）")
        return state(browser.click(index))
    }

    private suspend fun type(browser: BrowserService, args: JSONObject): String {
        val index = args.optInt("index", -1)
        if (index < 0) return invalid("index 必填（取自 find_elements 的编号）")
        if (!args.has("text")) return invalid("text 必填（可以为空字符串表示清空）")
        return state(browser.type(index, args.optString("text"), args.optBoolean("submit", false)))
    }

    private suspend fun scroll(browser: BrowserService, args: JSONObject): String {
        val direction = args.optString("direction", "down").lowercase()
        if (direction != "up" && direction != "down") return invalid("direction 只能是 up/down")
        return state(browser.scroll(direction, args.optDouble("amount", 1.0)))
    }

    /**
     * 截图工具：它是唯一一个"结果是图片"的工具。
     *
     * 走 [Tool.invokeResult] 而不是把 base64 塞进文本——模型看图片和执行工具用的是两条通道，
     * 把图当字符串塞回去只会浪费上下文，而且模型看不见。
     */
    private class ScreenshotTool(private val browser: BrowserService) : Tool {
        override val spec = SCREENSHOT

        override suspend fun invoke(argumentsJson: String): String = invokeResult(argumentsJson).text

        override suspend fun invokeResult(argumentsJson: String): ToolResult {
            val base64 = try {
                browser.screenshot()
            } catch (error: Throwable) {
                null
            } ?: return ToolResult(
                text = JSONObject().put("ok", false).put("error", "FAILED")
                    .put("message", "截图失败：页面还没渲染出可见内容").toString(),
            )
            val page = browser.state()
            return ToolResult(
                text = JSONObject().put("url", page.url).put("title", page.title)
                    .put("mime_type", "image/jpeg").toString(),
                images = listOf(AgentImage(mimeType = "image/jpeg", base64 = base64, name = "browser.jpg")),
            )
        }
    }

    /**
     * 窗口管理：`list` / `new` / `select` / `close`。
     *
     * 四个动作用一个工具，理由是它们本来就是同一件事的四个面：`list` 给出 id，
     * 另外三个都吃这个 id。拆成四个工具只会让模型多记三个名字。
     */
    private suspend fun tabs(browser: BrowserService, args: JSONObject): String {
        when (val action = args.optString("action", "list").lowercase()) {
            "list" -> Unit
            "new" -> {
                val url = args.optString("url").trim()
                browser.openTab(url.ifEmpty { null })
            }

            "select", "close" -> {
                val id = args.optLong("id", -1L)
                if (id <= 0) return invalid("id 必填（取自 action=list 的 windows[].id）")
                if (action == "select") browser.selectTab(id) else browser.closeTab(id)
            }

            else -> return invalid("action 只能是 list / new / select / close（收到 $action）")
        }
        val snapshot = browser.snapshot()
        val windows = JSONArray()
        snapshot.tabs.forEach { tab ->
            windows.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("active", tab.id == snapshot.activeId)
                    .put("loading", tab.loading)
                    .put("can_go_back", tab.canGoBack),
            )
        }
        return JSONObject()
            .put("active", snapshot.activeId)
            .put("max", snapshot.maxTabs)
            .put("windows", windows)
            .toString()
    }

    private suspend fun waitFor(browser: BrowserService, args: JSONObject): String {
        val selector = args.optString("selector").trim()
        if (selector.isEmpty()) return invalid("selector 必填（CSS 选择器）")
        val timeout = args.optLong("timeout_ms", 8_000L)
        val found = browser.waitFor(selector, timeout)
        return JSONObject().put("found", found).put("selector", selector).toString()
    }

    // ---------------------------------------------------------------- 辅助

    private fun PageElement.toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("tag", tag)
        .put("kind", kind)
        .put("text", text)
        .put("typeable", typeable)
        .put("input_type", inputType)

    private fun limit(args: JSONObject, fallback: Int): Int =
        args.optInt("limit", fallback).takeIf { it > 0 } ?: fallback

    private fun normalize(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) url
        else "https://$url"

    private fun tool(spec: ToolSpec, block: suspend (JSONObject) -> String): Tool =
        object : Tool {
            override val spec = spec
            override suspend fun invoke(argumentsJson: String): String = try {
                block(JSONObject(argumentsJson.ifBlank { "{}" }))
            } catch (error: Throwable) {
                JSONObject()
                    .put("ok", false)
                    .put("error", if (error is SecurityException) "PERMISSION_DENIED" else "FAILED")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            }
        }

    private fun invalid(reason: String) =
        JSONObject().put("ok", false).put("error", "INVALID_ARGUMENT").put("message", reason).toString()

    // ---------------------------------------------------------------- 规格

    private val OPEN = ToolSpec(
        name = "browser_open",
        description = "在内置浏览器里打开一个网址并等它加载完。后续的 click/type/scroll 都作用在这个页面上；" +
            "页面会保留登录状态。",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"网址，可省略 https://"}},""" +
            """"required":["url"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val STATE = ToolSpec(
        name = "browser_state",
        description = "当前页面在哪（网址、标题、能不能前进后退），不重新加载。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val READABLE = ToolSpec(
        name = "browser_readable",
        description = "把当前页面读成可读正文（去标签、压空白），用于\"这页说了什么\"。",
        parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","minimum":500,"maximum":40000}},""" +
            """"additionalProperties":false}""",
    )

    private val TEXT = ToolSpec(
        name = "browser_text",
        description = "取当前页面原始可见文本（保留换行结构），用于需要精确定位内容的场合。",
        parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","minimum":500,"maximum":40000}},""" +
            """"additionalProperties":false}""",
    )

    private val FIND = ToolSpec(
        name = "browser_find",
        description = "列出页面上可点/可输入的元素，每个带一个编号。点之前先找——编号只在这一次结果里有效。",
        parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":120}},""" +
            """"additionalProperties":false}""",
    )

    private val CLICK = ToolSpec(
        name = "browser_click",
        description = "点第 index 个元素（编号来自最近一次 browser_find）。页面变了编号就失效，会直接提示重找。",
        parametersJson = """{"type":"object","properties":{"index":{"type":"integer","minimum":0}},""" +
            """"required":["index"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val TYPE = ToolSpec(
        name = "browser_type",
        description = "往第 index 个输入框写文本；submit=true 时随后回车提交（会触发页面自己的提交逻辑）。",
        parametersJson = """{"type":"object","properties":{""" +
            """"index":{"type":"integer","minimum":0},"text":{"type":"string"},""" +
            """"submit":{"type":"boolean","default":false}},""" +
            """"required":["index","text"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SCROLL = ToolSpec(
        name = "browser_scroll",
        description = "把页面向上或向下滚 amount 屏（0.1–5，默认 1）。",
        parametersJson = """{"type":"object","properties":{""" +
            """"direction":{"type":"string","description":"up 或 down"},""" +
            """"amount":{"type":"number","minimum":0.1,"maximum":5}},""" +
            """"required":["direction"],"additionalProperties":false}""",
    )

    private val SCREENSHOT = ToolSpec(
        name = "browser_screenshot",
        description = "给当前页面截图，直接以图片返回（模型能看见布局）。用于判断\"这个按钮长什么样\"或验证操作结果。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val BACK = ToolSpec(
        name = "browser_back",
        description = "后退一页。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val FORWARD = ToolSpec(
        name = "browser_forward",
        description = "前进一页。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val RELOAD = ToolSpec(
        name = "browser_reload",
        description = "重新加载当前页面。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val TABS = ToolSpec(
        name = "browser_tabs",
        description = "管理浏览器的多个窗口：action=list 列出全部窗口，new 开一个新窗口（可带 url），" +
            "select 切到某个窗口，close 关掉某个窗口。所有 browser_* 动作只作用于当前窗口。",
        parametersJson = """{"type":"object","properties":{"""" +
            """"action":{"type":"string","description":"list \/ new \/ select \/ close，默认 list"},""" +
            """"url":{"type":"string","description":"action=new 时可选，新窗口直接打开的网址"},""" +
            """"id":{"type":"integer","description":"窗口 id，select 与 close 必填"}},""" +
            """"additionalProperties":false}""",
    )

    private val WAIT = ToolSpec(
        name = "browser_wait",
        description = "等某个 CSS 选择器出现（默认 8 秒）。异步加载的列表、弹窗用它，别用 sleep 猜。",
        parametersJson = """{"type":"object","properties":{""" +
            """"selector":{"type":"string","description":"CSS 选择器"},""" +
            """"timeout_ms":{"type":"integer","minimum":500,"maximum":30000}},""" +
            """"required":["selector"],"additionalProperties":false}""",
    )
}
