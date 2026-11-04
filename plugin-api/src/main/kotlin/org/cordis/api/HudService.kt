package org.cordis.api

import org.cordis.ServiceKey

/**
 * 屏幕上"我正在动"的提示：一颗可操作的胶囊。
 *
 * 为什么需要它：屏幕操作不再逐个弹审批（那会把自动化退化成手动遥控），代价是用户看不见
 * agent 在做什么，也不能中途叫停。这个服务的职责就是把这两件事补回来——**看得见，随时停**。
 *
 * 实现由宿主提供：只有 `AccessibilityService` 能在不额外申请悬浮窗权限的情况下，
 * 以 [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 盖在别人之上。
 */
interface HudService {

    /** 无障碍服务在跑、提示层可以挂上去。为 false 时 [show] 什么也不做。 */
    val available: Boolean

    /** 显示或更新当前状态。同一个状态重复调用应当不出任何可见变化。 */
    fun show(state: HudState)

    /**
     * 在屏幕上打一个落点：刚才在 (x, y) 发生了一次操作。
     *
     * 和 [show] 是两条独立的通道，互不覆盖：[show] 说的是"整体进行到哪一步"，
     * 这个是"刚才动的是哪里"。工具在真正点击/滑动时调用，不需要管胶囊当前写着什么。
     */
    fun pulse(pulse: HudPulse)

    /**
     * 由真正能控制执行的一方（brain）注册，接管胶囊上的两个按钮。
     *
     * 传 null 表示当前没人能接管——胶囊上的按钮会灰掉，而不是按下去没反应。
     * 这个设计让"显示层"和"控制层"解耦：宿主只负责把点击转成 [HudController] 调用，
     * 不知道暂停是怎么实现的；brain 只实现暂停，不知道按钮长什么样。
     */
    fun attachController(controller: HudController?)

    /** 收起。没有显示时调用是空操作。 */
    fun hide()

    companion object {
        val Key = ServiceKey<HudService>("host/hud")
    }
}

/** 胶囊上两个按钮的回调，由 brain 实现。 */
interface HudController {

    /** 左键：暂停 ⇄ 继续。实现必须幂等——按钮可能被连点。 */
    fun onTogglePause()

    /** 右键：停止当前任务。停止不是暂停，之后不再自动继续。 */
    fun onStop()
}

/**
 * 一颗胶囊里能放下的东西。字段刻意少：这是一行进度，不是日志。
 */
data class HudState(
    /** 第几步，从 1 开始；0 表示"这一轮刚开始，还没调工具"。 */
    val step: Int = 0,
    /** 正在跑的工具名，null 表示在等模型生成。 */
    val tool: String? = null,
    /** 一句话说明，例如"点「设置」"。给不出就留空，胶囊会退回到只显示工具名。 */
    val note: String? = null,
    /**
     * 第二行的状态词：思考中 / 执行中 / 操作中 / 已暂停 / 已停止。
     *
     * 用字符串而不是枚举：这是给人看的文案，不是状态机。真正影响行为的是 [phase]。
     */
    val status: String = "思考中",
    /** 决定左键长什么样：运行中显示黄点，暂停后变绿点。 */
    val phase: HudPhase = HudPhase.RUNNING,
)

/** 胶囊当前处于哪个阶段。只影响外观和左键的语义。 */
enum class HudPhase {
    RUNNING,
    PAUSED,
    STOPPED,
}
