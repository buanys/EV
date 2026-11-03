package ev.buan.plugins.agent.device

import android.content.res.Resources
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.delay
import org.cordis.api.ScreenAction
import org.cordis.api.ScreenDump
import org.cordis.api.ScreenService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕动作的闭环：读屏 → 定位 → 动作 → 复查。
 *
 * 原来的 [ScreenTools] 是一次性的：dump 一次拿坐标，tap 一次就算完。模型从 dump 到点击之间
 * 界面可能已经变了（动画、加载、别的应用弹窗），点错位置没有任何提示；点对了也不知道有没有
 * 生效，只能再 dump 一次自己比对。一轮对话的往返预算，大半耗在这种重复读屏上。
 *
 * 这里补三件事：
 *  - [GuiLines] 把 dump 的文本行解析回结构，于是动作可以按**序号**定位，并在动手前重新核对；
 *  - [GuiDiff] 让每个动作自己汇报「界面变了没有、是跳页还是滚动」；
 *  - `screen_wait` 把「点完等界面」从模型的多次往返压成一次调用。
 *
 * [ScreenService] 是宿主发布的契约，这里一行都不改：所有能力都建立在 dump + swipe 之上。
 */

/** 一行 dump 解析出来的节点。 */
internal data class GuiNode(
    /** dump 里的序号，只在这一次 dump 里有效。 */
    val index: Int,
    /** 节点文字；没有文字时宿主写 `·`。 */
    val label: String,
    val x: Int,
    val y: Int,
    /** `c` 可点、`e` 可编辑、`v` 选中、`o` 未选中。 */
    val flags: String,
    /** 输入框的提示文字。 */
    val hint: String?,
) {
    val clickable: Boolean get() = flags.contains('c')
    val editable: Boolean get() = flags.contains('e')
}

/**
 * `12 确定 @540,1200 c` 这行的解析。
 *
 * 不写成一条大正则：文字里合法地会出现 `@`、空格和数字（"发送 @张三 2 条消息"），
 * 从右往左找最后一个 ` @数字,数字` 才不会把正文当坐标。
 */
internal object GuiLines {

    fun parse(line: String): GuiNode? {
        var rest = line.trim()
        if (rest.isEmpty()) return null

        var hint: String? = null
        val hintAt = rest.indexOf(" hint=")
        if (hintAt > 0) {
            hint = rest.substring(hintAt + 6).trim()
            rest = rest.substring(0, hintAt)
        }

        // 坐标永远在行尾的标记之前，所以取最后一个 " @x,y"。
        val at = rest.lastIndexOf(" @")
        if (at <= 0) return null

        val head = rest.substring(0, at)
        val tail = rest.substring(at + 2)
        val fields = tail.split(' ').filter { it.isNotEmpty() }
        val xy = fields.firstOrNull()?.split(',') ?: return null
        if (xy.size != 2) return null
        val x = xy[0].trim().toIntOrNull() ?: return null
        val y = xy[1].trim().toIntOrNull() ?: return null

        val space = head.indexOf(' ')
        if (space <= 0) return null
        val index = head.substring(0, space).toIntOrNull() ?: return null
        val label = head.substring(space + 1)

        return GuiNode(index, label, x, y, fields.getOrNull(1).orEmpty(), hint)
    }

    fun all(dump: ScreenDump): List<GuiNode> = dump.lines.mapNotNull(::parse)
}

/**
 * 动作前后的差异，作为动作结果的附言。
 *
 * 三个信号分开给，因为模型需要的判断不一样：
 *  - 文字增删 → 页面真的变了；
 *  - 只有坐标变 → 滚动或重排，内容没换；
 *  - 什么都没变 → 这一下没生效，别再原地重复。
 */
internal object GuiDiff {

    fun describe(before: ScreenDump?, after: ScreenDump, limit: Int = 6): JSONObject {
        val out = JSONObject()
            .put("package", after.packageName)
            .put("nodes", after.lines.size)
        if (before == null) return out

        val beforeSigs = before.lines.map(::signature).toSet()
        val afterSigs = after.lines.map(::signature).toSet()
        val added = afterSigs - beforeSigs
        val removed = beforeSigs - afterSigs
        val moved = added.isEmpty() && removed.isEmpty() && moved(before, after)

        return out
            .put("packageChanged", before.packageName != after.packageName)
            .put("added", JSONArray(added.take(limit).toList()))
            .put("removed", JSONArray(removed.take(limit).toList()))
            .put("layoutMoved", moved)
            .put("unchanged", added.isEmpty() && removed.isEmpty() && !moved)
    }

    /** 文字和标记相同、只是位置变了。序号不算：滚动后序号会整体重排，那不是"变了"。 */
    private fun signature(line: String): String =
        GuiLines.parse(line)?.let { "${it.label}|${it.flags}" } ?: line.trim()

    private fun moved(before: ScreenDump, after: ScreenDump): Boolean {
        val old = GuiLines.all(before).associate { it.label to (it.x to it.y) }
        return GuiLines.all(after).any { node ->
            old[node.label]?.let { (x, y) -> x != node.x || y != node.y } == true
        }
    }
}

/** 屏幕像素尺寸：滚动要算手势，不能让模型去猜分辨率。 */
internal object GuiScreen {

    fun size(): Pair<Int, Int> = runCatching {
        val metrics = Resources.getSystem().displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }.getOrElse { 1080 to 2400 }
}

/** 屏幕服务的两种未就绪状态，文案在 [ScreenTools] 里已经有，这里只取用。 */
internal fun missingScreenService(): String =
    "屏幕服务未启用：宿主没有发布 ScreenService（宿主版本过旧）"

internal fun screenNotEnabled(): String =
    "无障碍未开启：在 设置 → 无障碍 → 已下载的服务 里打开 EV。" +
        "这是系统级授权，普通权限（存储、通知）不算。"

internal fun screenError(message: String): String =
    JSONObject().put("ok", false).put("error", message).toString()

/**
 * `screen_wait`：等界面变化。
 *
 * 只读，不是危险动作——它不改变任何应用的状态，只是反复 dump。
 * 上限 25 秒是给宿主的工具超时（30 秒）留余量。
 */
internal class ScreenWaitTool(private val service: () -> ScreenService?) : Tool {

    override val spec = ToolSpec(
        name = "screen_wait",
        description = "等界面变：等某段文字出现（点完按钮、等页面加载完）或消失（等弹窗关掉）。" +
            "命中就立刻返回当前屏幕；超时会明确说超时，并附上现在屏幕上的内容。" +
            "点完东西别急着再 dump 一次，先用它。只读，不改任何东西。",
        parametersJson = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"要等的文字，和屏幕上显示的一致，可以只给其中一段"},
              "mode":{"type":"string","enum":["appear","disappear"],"description":"等它出现还是等它消失，默认 appear"},
              "timeout_ms":{"type":"integer","description":"最多等多久，默认 8000，上限 25000"}
            },"required":["text"],"additionalProperties":false}
        """.trimIndent(),
    )

    override suspend fun invoke(argumentsJson: String): String {
        val service = service() ?: return screenError(missingScreenService())
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
        val text = args.optString("text", "").trim()
        if (text.isEmpty()) return screenError("缺少 text")
        val mode = args.optString("mode", "appear")
        val timeout = args.optInt("timeout_ms", 8_000).coerceIn(500, 25_000)

        val started = System.currentTimeMillis()
        var polls = 0

        while (true) {
            val dump = service.dump(400)
            dump.error?.let { return screenError(it) }
            polls++

            val present = dump.lines.any { it.contains(text) }
            val satisfied = if (mode == "disappear") !present else present
            val elapsed = System.currentTimeMillis() - started

            if (satisfied) {
                return JSONObject()
                    .put("ok", true)
                    .put("matched", true)
                    .put("elapsedMs", elapsed)
                    .put("polls", polls)
                    .put("package", dump.packageName)
                    .put("lines", JSONArray(dump.lines))
                    .toString()
            }

            if (elapsed >= timeout) {
                return JSONObject()
                    .put("ok", false)
                    .put("matched", false)
                    .put("timeout", true)
                    .put("elapsedMs", elapsed)
                    .put("polls", polls)
                    .put("package", dump.packageName)
                    .put(
                        "error",
                        "等了 ${elapsed}ms，「$text」" +
                            (if (mode == "disappear") "还留在屏幕上" else "没出现") +
                            "。下面是现在的屏幕，别原样再等一次。",
                    )
                    .put("lines", JSONArray(dump.lines))
                    .toString()
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        /** 200ms：比动画间隔短，又不必逐帧轮询。 */
        const val POLL_INTERVAL_MS = 200L
    }
}

/**
 * `screen_scroll`：按方向滚动，算好手势再调用宿主。
 *
 * 存在的理由是 `screen_swipe` 要求模型知道分辨率，它不知道，只能瞎写 500/1500 这种数字。
 * 方向语义跟阅读习惯一致：down = 显示下方内容。
 */
internal class ScreenScrollTool(private val service: () -> ScreenService?) : Tool {

    override val spec = ToolSpec(
        name = "screen_scroll",
        description = "按方向滚动当前界面：down=看下面（内容往上走），up=看上面，left/right=横向翻页。" +
            "不用给坐标，会按屏幕尺寸自己算。滚动完会告诉你内容变了没有。" +
            "高危：会改变其他应用的浏览位置。",
        parametersJson = """
            {"type":"object","properties":{
              "direction":{"type":"string","enum":["down","up","left","right"],"description":"要显示哪个方向的内容"},
              "amount":{"type":"string","enum":["page","small"],"description":"page=整屏（默认），small=小幅度，用来把目标挪进视野"}
            },"required":["direction"],"additionalProperties":false}
        """.trimIndent(),
        dangerous = true,
    )

    override suspend fun invoke(argumentsJson: String): String {
        val service = service() ?: return screenError(missingScreenService())
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
        val direction = args.optString("direction", "").trim()
        if (direction !in setOf("down", "up", "left", "right")) {
            return screenError("direction 只能是 down / up / left / right")
        }
        val ratio = if (args.optString("amount", "page") == "small") 0.18 else 0.45

        val (width, height) = GuiScreen.size()
        if (width <= 0 || height <= 0) return screenError("读不到屏幕尺寸，改用 screen_swipe 给坐标")

        val cx = width / 2
        val cy = height / 2
        val dx = (width * ratio).toInt()
        val dy = (height * ratio).toInt()

        val before = service.dump(400)
        before.error?.let { return screenError(it) }

        val action = when (direction) {
            // 看下面 = 手指从下往上推。
            "down" -> service.swipe(cx, cy + dy, cx, cy - dy, SWIPE_MS)
            "up" -> service.swipe(cx, cy - dy, cx, cy + dy, SWIPE_MS)
            "right" -> service.swipe(cx + dx, cy, cx - dx, cy, SWIPE_MS)
            else -> service.swipe(cx - dx, cy, cx + dx, cy, SWIPE_MS)
        }

        val outcome = ScreenTools.actionJson(action)
        if (action != ScreenAction.Ok) return screenError(outcome)

        delay(SETTLE_MS)
        val after = service.dump(400)
        return JSONObject()
            .put("ok", true)
            .put("direction", direction)
            .put("after", GuiDiff.describe(before, after))
            .put("lines", JSONArray(after.lines))
            .toString()
    }

    private companion object {
        /** 400ms 够走完一屏，又不至于甩出惯性滚动。 */
        const val SWIPE_MS = 400L

        /** 手势返回不代表滚动动画结束，等一小段再读屏。 */
        const val SETTLE_MS = 350L
    }
}
