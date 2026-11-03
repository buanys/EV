package ev.buan.plugins.agent.gui

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.delay
import org.cordis.api.ScreenService
import org.json.JSONObject

/**
 * `screen_read_all`：把一屏放不下的内容读完。
 *
 * 这是 GUI 层里唯一"跨屏"的工具，也是模型最容易手工做砸的一件事。原来的做法是
 * 模型自己循环 scroll → dump → 记住刚才读过什么，一旦忘了就重复读，一旦漏了就少读一段。
 * 而且每轮都是一次完整的工具往返。
 *
 * 这里把循环搬进宿主侧：反复滚动、按出现顺序累积、按文字签名去重，直到"滚了但没有新内容"
 * 或页数/字数到顶。模型拿到的是一段连续的正文，不是 N 页原始节点。
 *
 * 去重的代价是**重复的相同文字只出现一次**（比如聊天里同一句发了两遍）。这是为长列表付的
 * 代价，工具说明里写清楚，模型知道什么时候不该用它。
 */
internal class GuiReadAllTool(private val service: () -> ScreenService?) : Tool {

    override val spec = ToolSpec(
        name = "screen_read_all",
        description = "把一屏放不下的内容读完：反复滚动 + 读屏，按顺序合并去重，直到滚不动或没有新内容。" +
            "适合聊天记录、文章、长列表。返回的 text 是按出现顺序拼好的正文；太长会截断并标 truncated。" +
            "注意：完全相同的两三行只会出现一次；它只读不点，但界面会跟着滚动。",
        parametersJson = """
            {"type":"object","properties":{
              "direction":{"type":"string","enum":["down","up"],"description":"往哪翻，默认 down（看后面/更早的内容用 up）"},
              "max_pages":{"type":"integer","description":"最多翻几页，默认 8，上限 20"},
              "max_chars":{"type":"integer","description":"正文最多多少字符，默认 6000"},
              "settle_ms":{"type":"integer","description":"每页滚完等多久再读，默认 350"}
            },"additionalProperties":false}
        """.trimIndent(),
    )

    override suspend fun invoke(argumentsJson: String): String {
        val service = service() ?: return screenError(missingScreenService())
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
        val direction = if (args.optString("direction", "down") == "up") "up" else "down"
        val maxPages = args.optInt("max_pages", 8).coerceIn(1, 20)
        val maxChars = args.optInt("max_chars", 6_000).coerceIn(500, 20_000)
        val settle = args.optLong("settle_ms", 350).coerceIn(100, 1_500)

        val seen = LinkedHashSet<String>()
        val ordered = StringBuilder()
        var pages = 0
        var scrolled = 0
        var stop = "max_pages"
        var truncated = false

        while (pages < maxPages) {
            val dump = service.dump(500)
            dump.error?.let { return screenError(it) }
            pages++

            val fresh = GuiLines.all(dump)
                .filter { it.label.isNotEmpty() && it.label != "·" }
                .filter { seen.add("${it.label}|${it.flags}") }

            if (fresh.isEmpty()) {
                // 第一页就没有文字，说明这不是个能读的界面；翻过了还没新内容，说明到底了。
                stop = if (pages == 1) "empty" else "bottom"
                break
            }

            var overflow = false
            for (node in fresh) {
                if (ordered.length + node.label.length + 1 > maxChars) {
                    overflow = true
                    break
                }
                ordered.appendLine(node.label)
            }
            if (overflow) {
                truncated = true
                stop = "max_chars"
                break
            }

            scrollOnce(service, direction)
            scrolled++
            delay(settle)
        }

        return JSONObject()
            .put("ok", true)
            .put("direction", direction)
            .put("pages", pages)
            .put("scrolled", scrolled)
            .put("stop", stop)
            .put("truncated", truncated)
            .put("chars", ordered.length)
            .put("text", ordered.toString().trimEnd())
            .toString()
    }

    /** 与 `screen_scroll` 同一个手势：整屏 45%，400ms。翻不动时下一轮 dump 会判成 bottom。 */
    private fun scrollOnce(service: ScreenService, direction: String) {
        val (width, height) = GuiScreen.size()
        val cx = width / 2
        val cy = height / 2
        val dy = (height * 0.45).toInt()
        if (direction == "up") {
            service.swipe(cx, cy - dy, cx, cy + dy, SWIPE_MS)
        } else {
            service.swipe(cx, cy + dy, cx, cy - dy, SWIPE_MS)
        }
    }

    private companion object {
        const val SWIPE_MS = 400L
    }
}
