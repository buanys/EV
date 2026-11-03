package org.cordis.api

import org.cordis.ServiceKey

/**
 * 屏幕上"我正在动"的提示：一圈边缘跑马灯 + 一颗进度胶囊。
 *
 * 为什么需要它：屏幕操作不再逐个弹审批（那会把自动化退化成手动遥控），代价是用户看不见
 * agent 在做什么。这个服务的职责就是把那层可见性补回来——**免审批的前提是看得见**。
 *
 * 实现由宿主提供：只有 `AccessibilityService` 能在不额外申请悬浮窗权限的情况下，
 * 以 [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 盖在别人之上。
 *
 * 调用是轻量的、可丢的：`show` 可能在每个工具调用前被叫一次，实现必须合并更新而不是
 * 反复建窗口；`hide` 之后就当没显示过。
 */
interface HudService {

    /** 无障碍服务在跑、提示层可以挂上去。为 false 时 [show] 什么也不做。 */
    val available: Boolean

    /** 显示或更新当前状态。同一个状态重复调用应当不出任何可见变化。 */
    fun show(state: HudState)

    /** 收起。没有显示时调用是空操作。 */
    fun hide()

    companion object {
        val Key = ServiceKey<HudService>("host/hud")
    }
}

/**
 * 一颗胶囊里能放下的东西。字段刻意少：这是一行进度，不是日志。
 */
data class HudState(
    /** 第几步，从 1 开始；0 表示"这一轮刚开始，还没调工具"。 */
    val step: Int = 0,
    /** 正在跑的工具名，null 表示在等模型生成。 */
    val tool: String? = null,
    /** 一句话说明，例如"正在读屏"。给不出就留空，胶囊会退回到只显示步骤。 */
    val note: String? = null,
)
