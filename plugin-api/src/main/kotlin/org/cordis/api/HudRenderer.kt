package org.cordis.api

import android.graphics.Canvas
import org.cordis.ServiceKey

/**
 * 屏幕提示层的**视觉**，由插件实现；**窗口**由宿主负责。
 *
 * 为什么这样切：只有 `AccessibilityService` 能用 `TYPE_ACCESSIBILITY_OVERLAY` 加窗口，
 * 而插件拿不到 `Context`、也拿不到 `WindowManager`——这一层搬不动。但"画什么"完全可以搬：
 * 胶囊的形状、光带的颜色、呼吸的周期，都是纯绘制，宿主只递一块画布过来。
 *
 * 搬过去之后，改视觉只需要重编一个插件 jar，不用重装 APK、不用重启宿主。
 *
 * 两层是分开的，因为它们的窗口尺寸根本不同：
 *  - [drawFrame] 拿到的画布是全屏（边框跑马灯）；
 *  - [measurePill] / [drawPill] 是一颗 `WRAP_CONTENT` 的小窗口，宿主按返回的尺寸摆放。
 *
 * 时间从 [elapsedMs] 进来而不是由实现自己记：动画就是 `f(elapsed)`，换成固定时间戳就能
 * 逐帧复现，不必去猜哪一帧漏了。
 */
interface HudRenderer {

    /** 胶囊这一帧需要多大（像素）。宿主用它设置窗口大小，所以必须包含全部内边距。 */
    fun measurePill(state: HudState, maxWidthPx: Int): HudSize

    /** 在尺寸为 [measurePill] 结果的画布上画胶囊。 */
    fun drawPill(canvas: Canvas, state: HudState, elapsedMs: Long)

    /** 全屏画布：边框光。什么都不画就是没有这一层。 */
    fun drawFrame(canvas: Canvas, state: HudState, elapsedMs: Long)

    /**
     * 至少多久刷新一次。跑马灯要连续，胶囊只有呼吸点需要动，实现自己定。
     * 返回 0 表示静态，宿主可以在首帧之后停止重绘。
     */
    fun refreshIntervalMs(): Long = 16L

    companion object {
        val Key = ServiceKey<HudRenderer>("host/hud-renderer")
    }
}

/** 像素尺寸。宿主按它给胶囊开窗口。 */
data class HudSize(val width: Int, val height: Int)
