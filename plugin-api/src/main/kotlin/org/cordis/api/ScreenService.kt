package org.cordis.api

import org.cordis.ServiceKey

/**
 * 屏幕操作：读屏、点按、输入。
 *
 * 为什么必须由宿主提供：`AccessibilityService` 是 Android 的系统级服务，只能由应用自己
 * 注册、由用户在系统设置里手动开启；插件拿不到 `Context`，更无法注册服务。
 *
 * **这是一条需要用户明确授权的链路**：开启「无障碍」意味着 agent 能看到屏幕上的所有文字
 * （包括密码框之外的界面内容）、能在任何应用里点按和输入。所以：
 *  - 未授权时 [available] 为 false，所有操作返回失败原因，绝不静默；
 *  - 工具层把这组操作当作**危险动作**走审批门（它们会改变其他应用的状态）。
 *
 * 设计上只给"读 + 最基本的操作"，不做投屏、不读输入框里的明文以外的隐私内容。
 */
interface ScreenService {

    /** 无障碍服务是否已连接（用户已开启且服务在跑）。 */
    val available: Boolean

    /** 打开系统的无障碍设置页，让用户就地开启。返回是否成功拉起。 */
    fun openSettings(): Boolean

    /**
     * 当前窗口的可访问性节点树，压平成文本行。
     *
     * 每条是一行：`序号 text @x,y [标记]`，标记里 `c` 可点击、`e` 可编辑、`v` 选中。
     * 只给能点的、能读的、有文字的节点——无障碍树里绝大多数是不可交互的容器，
     * 原样交给模型只会烧掉上下文。
     */
    fun dump(maxNodes: Int = 200): ScreenDump

    /** 按坐标点击。坐标取自 [ScreenDump] 的行，节点位置会变，所以坐标要现用现取。 */
    fun tap(x: Int, y: Int): ScreenAction

    /** 按可见文字点击（找不到就失败，不猜坐标）。 */
    fun tapText(text: String): ScreenAction

    /** 往当前焦点输入框里打字。会先尝试追加，失败则回退为替换。 */
    fun input(text: String): ScreenAction

    /** 从 (x1,y1) 滑到 (x2,y2)，[durationMs] 控制快慢。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): ScreenAction

    /** 按一个系统键：[key] 取 "back" / "home" / "recents" / "notifications"。 */
    fun key(key: String): ScreenAction

    /** 无障碍服务最近看到的窗口包名——比 `DeviceContextService.currentApp()` 准确。 */
    fun currentPackage(): String?

    companion object {
        val Key = ServiceKey<ScreenService>("host/screen")
    }
}

/** 一次读屏的结果。 */
data class ScreenDump(
    /** 每行形如 `12 确定 @540,1200 c`。 */
    val lines: List<String>,
    /** 前台包名，读不到时为空串。 */
    val packageName: String,
    /** 节点太多被截断。调用方应当据此告诉模型"还有更多"。 */
    val truncated: Boolean = false,
    /** 读屏失败的原因（无障碍没开、没有活动窗口等）。 */
    val error: String? = null,
)

/** 一次屏幕操作的结果。 */
sealed interface ScreenAction {
    data object Ok : ScreenAction

    /** 无障碍没开启。 */
    data object NoAccessibility : ScreenAction

    /** 找不到目标（文字不存在、坐标无节点）。 */
    data class NotFound(val detail: String) : ScreenAction

    data class Failed(val reason: String) : ScreenAction
}
