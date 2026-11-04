package ev.buan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.cordis.api.ScreenAction
import org.cordis.api.ScreenDump
import org.cordis.api.ScreenService

/**
 * 无障碍服务：agent 的"眼睛和手"。
 *
 * 这是整个系统里权限最大的一项——开启后能看到任何应用界面上的文字，能在任何应用里点按输入。
 * 所以：
 *  - 服务自身**不做任何决定**，只在宿主调用时执行一次动作；
 *  - 是否允许调用由宿主的审批门（`ToolSpec.dangerous`）先把关，这里只负责"照做或如实说失败"；
 *  - 所有失败都带原因（没开、找不到、系统拒绝），不静默。
 *
 * 状态放在 companion：`AccessibilityService` 实例由系统创建和销毁，宿主没有句柄。
 */
class EvAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只关心窗口切换：记录当前前台包名，做 app_current 的可靠来源。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hudOrNull?.dispose()
        hudOrNull = null
        if (instance === this) instance = null
        connected = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 提示层

    /**
     * 边缘跑马灯 + 进度胶囊。
     *
     * 懒创建：没人用它的时候不该有窗口存在。销毁必须显式——overlay 窗口不会跟着服务
     * 自动消失，漏一个就留在屏幕上变成幽灵。
     */
    private var hudOrNull: HudOverlay? = null

    private fun hud(): HudOverlay = hudOrNull ?: HudOverlay(this).also { hudOrNull = it }

    internal fun showHud(state: org.cordis.api.HudState, renderer: org.cordis.api.HudRenderer?) =
        hud().show(state, renderer)

    internal fun hideHud() = hudOrNull?.hide()

    internal fun pulse(pulse: org.cordis.api.HudPulse, renderer: org.cordis.api.HudRenderer?) =
        hud().pulse(pulse, renderer)

    // ---------------------------------------------------------------- 读屏

    /**
     * 压平节点树。
     *
     * 只保留"有文字"或"能点/能编辑"的节点：无障碍树里绝大多数是不可交互的容器，
     * 原样交给模型会几万个字符还读不到重点。
     */
    internal fun dumpNodes(maxNodes: Int): ScreenDump {
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return ScreenDump(emptyList(), foregroundPackage.orEmpty(), error = "没有活动窗口（可能屏幕锁着或在系统桌面）")

        val lines = mutableListOf<String>()
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (lines.size >= maxNodes) {
                truncated = true
                return
            }
            // 深度护栏：某些应用会造出非常深的树。
            if (depth > MAX_DEPTH) return

            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val checkable = node.isCheckable

            if (text.isNotEmpty() || clickable || editable) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val flags = buildString {
                    if (clickable) append('c')
                    if (editable) append('e')
                    if (checkable) append(if (node.isChecked) 'v' else 'o')
                }
                val center = "@${rect.centerX()},${rect.centerY()}"
                lines += buildString {
                    append(lines.size).append(' ').append(text.ifEmpty { "·" })
                    append(' ').append(center)
                    if (flags.isNotEmpty()) append(' ').append(flags)
                    // 输入框的内容要单独标出来：模型得知道里面现在有什么才敢改。
                    if (editable && text.isEmpty()) {
                        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { append(" hint=").append(it) }
                    }
                }
            }

            for (index in 0 until node.childCount) {
                walk(node.getChild(index), depth + 1)
                if (lines.size >= maxNodes) {
                    truncated = true
                    break
                }
            }
        }

        runCatching { walk(root, 0) }
        // 包名以实际窗口为准，不用 foregroundPackage：那是事件流缓存的，提示层窗口出现时
        // 会被改写成 ev.buan，模型就会以为"当前在 EV 里"，然后对着错误的上下文做决定。
        val actualPackage = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
        runCatching { root.recycle() }
        return ScreenDump(
            lines,
            actualPackage.ifEmpty { foregroundPackage.orEmpty() },
            truncated,
            null,
        )
    }

    // ---------------------------------------------------------------- 操作

    internal fun tapAt(x: Int, y: Int): ScreenAction {
        if (!connected) return ScreenAction.NoAccessibility
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatch(gesture)
    }

    internal fun tapOnText(text: String): ScreenAction {
        if (!connected) return ScreenAction.NoAccessibility
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return ScreenAction.NotFound("没有活动窗口")
        val target = findNode(root, text)
            ?: return ScreenAction.NotFound("界面上找不到「$text」")

        // 优先走 ACTION_CLICK：它比注入坐标更可靠，也不会被"点在边缘"搞砸。
        val clicked = target.isClickable && runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) return ScreenAction.Ok

        // 节点自己不可点（常见于文字在子节点、点在父容器上）：沿父链找最近的可点祖先。
        var parent = target.parent
        while (parent != null) {
            if (parent.isClickable) {
                val ok = runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (ok) return ScreenAction.Ok
                break
            }
            parent = parent.parent
        }

        // 最后退化成按坐标点一次。
        val rect = android.graphics.Rect()
        target.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return ScreenAction.Failed("「$text」没有可点区域")
        return tapAt(rect.centerX(), rect.centerY())
    }

    private fun findNode(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
        // 精确匹配优先，包含匹配兜底：模型给的多半是完整文案，但也会漏掉省略号。
        if (label.equals(text, ignoreCase = true)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), text)?.let { return it }
        }
        if (label.contains(text, ignoreCase = true)) return node
        return null
    }

    internal fun inputText(text: String): ScreenAction {
        if (!connected) return ScreenAction.NoAccessibility
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return ScreenAction.NoAccessibility
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return ScreenAction.NotFound("没有找到输入框：先点一下要输入的位置")

        // append 失败时退化成替换——有些输入框不认 append。
        val appended = runCatching {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return if (appended) ScreenAction.Ok else ScreenAction.Failed("系统拒绝了这次输入")
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            findFirstEditable(node.getChild(index))?.let { return it }
        }
        return null
    }

    internal fun swipeFrom(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ScreenAction {
        if (!connected) return ScreenAction.NoAccessibility
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 2_000)))
            .build()
        return dispatch(gesture)
    }

    internal fun pressKey(key: String): ScreenAction {
        if (!connected) return ScreenAction.NoAccessibility
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return ScreenAction.Failed("不认识的键：$key（可用 back / home / recents / notifications）")
        }
        return if (runCatching { performGlobalAction(action) }.getOrDefault(false)) {
            ScreenAction.Ok
        } else {
            ScreenAction.Failed("系统拒绝了 $key")
        }
    }

    private fun dispatch(gesture: GestureDescription): ScreenAction = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ScreenAction.Failed("系统版本太低，不支持注入手势")
        }
        val ok = dispatchGesture(gesture, null, null)
        if (ok) ScreenAction.Ok else ScreenAction.Failed("系统拒绝了这次手势")
    }.getOrElse { ScreenAction.Failed(it.message ?: it.javaClass.simpleName) }

    companion object {
        private const val MAX_DEPTH = 40

        @Volatile
        private var connected: Boolean = false

        @Volatile
        private var instance: EvAccessibilityService? = null

        /** 无障碍最近一次窗口切换看到的包名。 */
        @Volatile
        private var foregroundPackage: String? = null

        fun isConnected(): Boolean = connected

        /** 用户是否在系统设置里开启过本服务。 */
        fun hasAccess(context: android.content.Context): Boolean {
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull() ?: return false
            val component = android.content.ComponentName(context, EvAccessibilityService::class.java)
            return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
        }

        fun openSettings(context: android.content.Context): Boolean = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

        fun currentPackage(): String? = foregroundPackage

        /** 提示层的门面：服务不在时只是少一层可见性，但必须留痕，否则"没显示"无从查起。 */
        fun showHud(state: org.cordis.api.HudState, renderer: org.cordis.api.HudRenderer?) {
            val service = instance
            if (service == null) {
                android.util.Log.w("EV", "HUD: 无障碍服务实例不在，跳过显示")
                return
            }
            service.showHud(state, renderer)
        }

        fun pulse(pulse: org.cordis.api.HudPulse, renderer: org.cordis.api.HudRenderer?) {
            instance?.pulse(pulse, renderer)
        }

        fun hideHud() {
            instance?.hideHud()
        }

        /** 给宿主服务用的门面：服务不在时所有操作统一返回 NoAccessibility。 */
        fun delegate(): ScreenService = object : ScreenService {
            override val available: Boolean get() = connected

            override fun openSettings(): Boolean = false // 需要 Context，由 ScreenServiceImpl 接管

            override fun dump(maxNodes: Int): ScreenDump =
                instance?.dumpNodes(maxNodes)
                    ?: ScreenDump(emptyList(), foregroundPackage.orEmpty(), error = NOT_ENABLED)

            override fun tap(x: Int, y: Int): ScreenAction =
                instance?.tapAt(x, y) ?: ScreenAction.NoAccessibility

            override fun tapText(text: String): ScreenAction =
                instance?.tapOnText(text) ?: ScreenAction.NoAccessibility

            override fun input(text: String): ScreenAction =
                instance?.inputText(text) ?: ScreenAction.NoAccessibility

            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ScreenAction =
                instance?.swipeFrom(x1, y1, x2, y2, durationMs) ?: ScreenAction.NoAccessibility

            override fun key(key: String): ScreenAction =
                instance?.pressKey(key) ?: ScreenAction.NoAccessibility

            override fun currentPackage(): String? = foregroundPackage
        }

        const val NOT_ENABLED =
            "无障碍未开启：在 设置 → 无障碍 → 已下载的服务 里打开 EV（这是系统级授权，普通权限不算）"
    }
}
