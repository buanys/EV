package ev.buan.plugins.agent.gui

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.HudService
import kotlinx.coroutines.delay
import org.cordis.api.ScreenAction
import org.cordis.api.ScreenDump
import org.cordis.api.ScreenService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕操作工具：读屏 + 点按 + 输入 + 滑动 + 系统键。
 *
 * **免审批，故意的。** 这一组的用途就是自动化：点一下弹一次确认，等于把自动化退化成
 * 手动遥控，用户已经开了无障碍，授权发生在系统设置那一步。所以：
 *  - 只读的 `screen_dump` / `screen_wait` 本来就不审；
 *  - 有后果的点击、输入、滑动、滚动也不审。
 *
 * 免审批的前提是**用户看得见**：宿主会在这些动作期间点亮屏幕边缘的跑马灯、在顶部挂一颗
 * 胶囊说明当前在做什么。没有那层可见性，这里就变成了用户看不见的暗中操作。
 *
 * 所有操作在无障碍未开启时统一返回 `NoAccessibility`，并附上"去哪开"，
 * 而不是抛异常或假装成功。
 */
internal object GuiTools {

    fun all(service: () -> ScreenService?, hud: () -> HudService?): List<Tool> = listOf(
        DumpTool(service),
        TapTool(service, hud),
        TapTextTool(service, hud),
        InputTool(service),
        SwipeTool(service, hud),
        KeyTool(service),
        // 闭环：等界面、按方向滚动。这两件原来要靠模型自己 dump + 算坐标 + 反复比对。
        GuiWaitTool(service),
        GuiScrollTool(service, hud),
        // 跨屏：一屏读不完的内容，翻页采集后一次交给模型。
        GuiReadAllTool(service),
    )

    /** 一次操作的落点提示。可丢：没有提示层时什么都不发生。 */
    private fun pulse(hud: () -> HudService?, x: Int, y: Int) {
        val service = hud() ?: return
        runCatching {
            service.pulse(
                org.cordis.api.HudPulse(x = x, y = y, startedAt = android.os.SystemClock.elapsedRealtime()),
            )
        }
    }

    private fun missing(): String =
        "屏幕服务未启用：宿主没有发布 ScreenService（宿主版本过旧）"

    private fun notEnabled(): String =
        "无障碍未开启：在 设置 → 无障碍 → 已下载的服务 里打开 EV。" +
            "这是系统级授权，普通权限（存储、通知）不算。"

    /** 统一的动作结果翻译。`internal` 是因为 [GuiScrollTool] 也要用同一套措辞。 */
    internal fun actionJson(action: ScreenAction): String = when (action) {
        ScreenAction.Ok -> JSONObject().put("ok", true).toString()
        ScreenAction.NoAccessibility -> error(notEnabled())
        is ScreenAction.NotFound -> error(action.detail)
        is ScreenAction.Failed -> error(action.reason)
    }

    private class DumpTool(private val service: () -> ScreenService?) : Tool {
        override val spec = ToolSpec(
            name = "screen_dump",
            description = "读取当前屏幕上的可交互内容：每行一个节点，「序号 文字 @x,y [标记]」。" +
                "标记 c=可点击 e=可编辑 v=已选中 o=未选中。要先看屏幕再决定点哪里时用它。" +
                "坐标只在这次 dump 里有效——界面一变就要重读。",
            parametersJson = """
                {"type":"object","properties":{
                  "max_nodes":{"type":"integer","description":"最多返回多少行，默认 200，上限 500"}
                },"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
            val maxNodes = args.optInt("max_nodes", 200).coerceIn(1, 500)

            val dump = service.dump(maxNodes)
            if (dump.error != null) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", dump.error)
                    .put("accessibility", service.available)
                    .toString()
            }
            return JSONObject()
                .put("ok", true)
                .put("package", dump.packageName)
                .put("count", dump.lines.size)
                .put("truncated", dump.truncated)
                .put("lines", JSONArray().apply { dump.lines.forEach { put(it) } })
                .toString()
        }
    }

    private class TapTool(
        private val service: () -> ScreenService?,
        private val hud: () -> HudService?,
    ) : Tool {
        override val spec = ToolSpec(
            name = "screen_tap",
            description = "点一下屏幕。优先用 node（screen_dump 里的序号）：点击前会重新读屏核对，" +
                "界面已经变了就报错，而不是点错地方。也可以给 x / y，但坐标从 dump 到点击之间随时失效。" +
                "duration_ms 超过 500 就是长按。返回里的 after 说明界面变没变、是跳页还是只是重排。" +
                "高危：会改变其他应用的状态。",
            parametersJson = """
                {"type":"object","properties":{
                  "node":{"type":"integer","description":"screen_dump 里的序号，推荐用这个"},
                  "expect":{"type":"string","description":"可选：核对这个序号上的文字是否包含这段内容，不符就不点"},
                  "x":{"type":"integer","description":"横坐标（不用 node 时）"},
                  "y":{"type":"integer","description":"纵坐标（不用 node 时）"},
                  "duration_ms":{"type":"integer","description":"按住时长，默认 60；500 以上算长按"}
                },"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return error("请求体不是合法 JSON")
            val duration = args.optInt("duration_ms", 60).coerceIn(30, 3_000)

            val node = args.optInt("node", -1)
            var before: ScreenDump?

            val (x, y) = if (node >= 0) {
                val dump = service.dump(500)
                dump.error?.let { return error(it) }
                before = dump
                val target = GuiLines.all(dump).firstOrNull { it.index == node }
                    ?: return error("screen_dump 里没有序号 $node：界面可能已经变了，先重新 screen_dump")
                val expect = args.optString("expect", "").trim()
                if (expect.isNotEmpty() && !target.label.contains(expect)) {
                    return error(
                        "序号 $node 现在是「${target.label}」，不含「$expect」，没有点。" +
                            "先重新 screen_dump 看看现在是什么，别照旧序号硬点。",
                    )
                }
                target.x to target.y
            } else {
                val px = args.optInt("x", -1)
                val py = args.optInt("y", -1)
                if (px < 0 || py < 0) return error("要么给 node，要么给 x / y")
                before = service.dump(300)
                px to py
            }

            // 落点先亮：pulse 是异步的，不会拖慢点击，用户看到的是"这里被按了一下"。
            pulse(hud, x, y)

            // 同点长按：单点路径 + 时长就是 Android 的长按手势，不必再单独开一个工具。
            val action = if (duration > 500) {
                service.swipe(x, y, x, y, duration.toLong())
            } else {
                service.tap(x, y)
            }
            val outcome = actionJson(action)
            if (action != ScreenAction.Ok) return outcome

            delay(SETTLE_MS)
            val after = service.dump(400)
            return JSONObject(outcome)
                .put("tapped", JSONObject().put("x", x).put("y", y))
                .put("after", GuiDiff.describe(before, after))
                .toString()
        }
    }

    private class TapTextTool(
        private val service: () -> ScreenService?,
        private val hud: () -> HudService?,
    ) : Tool {
        override val spec = ToolSpec(
            name = "screen_tap_text",
            description = "按屏幕上的文字点击，比坐标可靠（界面滚动后坐标失效，文字不会）。" +
                "找不到就明确失败，不会乱点。高危：会改变其他应用的状态。",
            parametersJson = """
                {"type":"object","properties":{
                  "text":{"type":"string","description":"要点的文字，例如「发送」「确定」"}
                },"required":["text"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val text = runCatching { JSONObject(argumentsJson).optString("text", "").trim() }.getOrDefault("")
            if (text.isEmpty()) return error("缺少 text")
            return actionJson(service.tapText(text))
        }
    }

    private class InputTool(private val service: () -> ScreenService?) : Tool {
        override val spec = ToolSpec(
            name = "screen_input",
            description = "往当前聚焦的输入框里输入文字。没有聚焦的输入框时，会找界面上第一个可编辑框；" +
                "都没有就失败。高危：会在任何应用的输入框里打字。",
            parametersJson = """
                {"type":"object","properties":{
                  "text":{"type":"string","description":"要输入的内容"}
                },"required":["text"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val text = runCatching { JSONObject(argumentsJson).optString("text", "") }.getOrDefault("")
            if (text.isEmpty()) return error("text 不能为空")
            return actionJson(service.input(text))
        }
    }

    private class SwipeTool(
        private val service: () -> ScreenService?,
        private val hud: () -> HudService?,
    ) : Tool {
        override val spec = ToolSpec(
            name = "screen_swipe",
            description = "在屏幕上滑动（滚动列表、翻页）。从 (x1,y1) 滑到 (x2,y2)。" +
                "向上滑 = 内容往下走，所以「看下面」是从下往上滑。高危。",
            parametersJson = """
                {"type":"object","properties":{
                  "x1":{"type":"integer","description":"起点横坐标"},
                  "y1":{"type":"integer","description":"起点纵坐标"},
                  "x2":{"type":"integer","description":"终点横坐标"},
                  "y2":{"type":"integer","description":"终点纵坐标"},
                  "duration_ms":{"type":"integer","description":"滑动时长，默认 300 毫秒"}
                },"required":["x1","y1","x2","y2"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return error("请求体不是合法 JSON")
            val x1 = args.optInt("x1", -1); val y1 = args.optInt("y1", -1)
            val x2 = args.optInt("x2", -1); val y2 = args.optInt("y2", -1)
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return error("缺少坐标")
            val duration = args.optLong("duration_ms", 300L)
            pulse(hud, x1, y1)
            return actionJson(service.swipe(x1, y1, x2, y2, duration))
        }
    }

    private class KeyTool(private val service: () -> ScreenService?) : Tool {
        override val spec = ToolSpec(
            name = "screen_key",
            description = "按系统键：back（返回）、home（回桌面）、recents（任务列表）、notifications（下拉通知栏）。高危。",
            parametersJson = """
                {"type":"object","properties":{
                  "key":{"type":"string","enum":["back","home","recents","notifications"],"description":"要按的键"}
                },"required":["key"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missing())
            val key = runCatching { JSONObject(argumentsJson).optString("key", "").trim() }.getOrDefault("")
            if (key.isEmpty()) return error("缺少 key")
            return actionJson(service.key(key))
        }
    }

    private fun error(message: String): String = JSONObject().put("ok", false).put("error", message).toString()

    /**
     * 手势返回之后到界面稳定之间的等待。
     *
     * 300ms 是折中：短了读到的还是旧界面（会把"点生效了"误报成 unchanged），
     * 长了每次点击都白等。真需要长时间等待应该用 screen_wait，不是把这里调大。
     */
    private const val SETTLE_MS = 300L
}
