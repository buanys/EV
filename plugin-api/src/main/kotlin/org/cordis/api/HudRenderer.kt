package org.cordis.api

import android.graphics.Canvas
import org.cordis.ServiceKey

/**
 * 屏幕提示层的**视觉**，由插件实现；**窗口**由宿主负责。
 *
 * 为什么这样切：只有 `AccessibilityService` 能用 `TYPE_ACCESSIBILITY_OVERLAY` 加窗口，
 * 而插件拿不到 `Context`、也拿不到 `WindowManager`——这一层搬不动。但"画什么"完全可以搬：
 * 胶囊的形状、涟漪的颜色、呼吸的周期，都是纯绘制，宿主只递一块画布过来。
 *
 * 时间从 [elapsedMs] 进来而不是由实现自己记：动画就是 `f(elapsed)`，换成固定时间戳就能
 * 逐帧复现，不必去猜哪一帧漏了。
 */
interface HudRenderer {

    /** 胶囊这一帧需要多大（像素）。宿主用它设置窗口大小，所以必须包含全部内边距。 */
    fun measurePill(state: HudState, maxWidthPx: Int): HudSize

    /** 在尺寸为 [measurePill] 结果的画布上画胶囊。 */
    fun drawPill(canvas: Canvas, state: HudState, elapsedMs: Long)

    /**
     * 全屏画布：把 [pulses] 画出来。
     *
     * 涟漪是**事件驱动**的，不是循环动画：每一次点击/滑动，在那个位置散开一圈，
     * 几百毫秒后消失。它回答的是"刚才动的是哪里"，而不是"我还活着"——
     * 后者由胶囊上的呼吸点回答。
     */
    fun drawPulses(canvas: Canvas, pulses: List<HudPulse>, elapsedMs: Long)

    /**
     * 胶囊上这一下点到了什么。坐标是胶囊内的像素坐标。
     *
     * 交互归渲染器，是因为按钮的位置和大小只有画的人知道——宿主如果硬编码两个圆形区域，
     * 换一套布局就会点错地方。宿主只负责把触摸坐标原样递过来。
     */
    fun hitTest(x: Float, y: Float, state: HudState, size: HudSize): HudHit = HudHit.NONE

    /**
     * 至少多久刷新一次。有涟漪在飞的时候宿主按这个间隔重绘；没有涟漪、胶囊又不呼吸时，
     * 返回 0 可以让宿主停掉重绘。
     */
    fun refreshIntervalMs(): Long = 16L

    companion object {
        val Key = ServiceKey<HudRenderer>("host/hud-renderer")
    }
}

/** 胶囊上被点中的区域。 */
enum class HudHit {
    NONE,

    /** 左键：暂停 ⇄ 继续。 */
    PAUSE_RESUME,

    /** 右键：停止。 */
    STOP,
}

/** 像素尺寸。宿主按它给胶囊开窗口。 */
data class HudSize(val width: Int, val height: Int)

/**
 * 一次"刚才在这里发生了事"。
 *
 * 坐标是屏幕像素。`width` / `height` 为 0 表示只知道一个点（例如按坐标点击），
 * 这时画涟漪就够了；非 0 表示知道目标控件的矩形，可以顺带描个框。
 */
data class HudPulse(
    val x: Int,
    val y: Int,
    val width: Int = 0,
    val height: Int = 0,
    /** 从这一刻开始算的相位（`SystemClock.elapsedRealtime()` 的毫秒）。 */
    val startedAt: Long,
)
