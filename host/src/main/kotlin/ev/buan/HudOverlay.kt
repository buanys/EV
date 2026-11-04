package ev.buan

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.cordis.api.HudRenderer
import org.cordis.api.HudSize
import org.cordis.api.HudState

/**
 * 提示层的**窗口**部分：挂两个窗口、摆好位置、把画布递给 [HudRenderer]。
 *
 * 这里一行绘制代码都没有——画什么归插件（agent-gui）。宿主只负责插件搬不动的事：
 * `TYPE_ACCESSIBILITY_OVERLAY` 只有 `AccessibilityService` 能加。
 *
 * 两个窗口而不是一个：`FLAG_BLUR_BEHIND` 一类的窗口属性是按**矩形**生效的，全屏窗口上
 * 开特效会糊整屏。边框光要全屏，胶囊要刚好包住自己，尺寸需求不同，只能分开。
 */
internal class HudOverlay(private val service: AccessibilityService) {

    private val density = service.resources.displayMetrics.density
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)

    private var frameView: FrameView? = null
    private var pillView: PillView? = null
    private var renderer: HudRenderer? = null

    fun show(state: HudState, painter: HudRenderer?) = onMain {
        if (painter == null) {
            // 没有视觉实现（agent-gui 没装或被禁用）就不显示，不要留一颗空白窗口在屏幕上。
            detachAll()
            return@onMain
        }
        renderer = painter
        val pill = ensurePill() ?: return@onMain
        ensureFrame()
        pill.render(state)
        frameView?.render(state)
    }

    fun hide() = onMain { detachAll() }

    /** 服务销毁时调用：窗口必须自己拆，否则会留在屏幕上变成幽灵。 */
    fun dispose() = onMain { detachAll() }

    private fun onMain(action: () -> Unit) {
        val posted = runCatching { service.mainExecutor.execute(action) }
        if (posted.isFailure) {
            android.util.Log.w("EV", "HUD 切主线程失败：${posted.exceptionOrNull()?.message}")
        }
    }

    // ---------------------------------------------------------------- 胶囊

    private fun ensurePill(): PillView? {
        pillView?.let { return it }
        val manager = windowManager ?: return null
        val created = PillView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (14 * density).toInt()
            title = "EV 屏幕操作提示"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsIgnoringVisibility(true)
            }
        }
        val added = runCatching { manager.addView(created, params) }
        if (added.isFailure) {
            android.util.Log.w("EV", "HUD 胶囊窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        pillView = created
        return created
    }

    // ---------------------------------------------------------------- 边框光

    private fun ensureFrame(): FrameView? {
        frameView?.let { return it }
        val manager = windowManager ?: return null
        val created = FrameView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "EV 屏幕操作边框"
            // FLAG_LAYOUT_NO_LIMITS 只允许窗口超出屏幕，**不阻止**系统按 inset 裁剪：
            // 默认 fitTypes 含状态栏和导航栏，窗口会从 y=140 开始，顶边永远没光。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsIgnoringVisibility(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val added = runCatching { manager.addView(created, params) }
        if (added.isFailure) {
            android.util.Log.w("EV", "HUD 跑马灯窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        frameView = created
        created.start()
        return created
    }

    private fun detachAll() {
        pillView?.let { runCatching { windowManager?.removeView(it) } }
        frameView?.let {
            it.stop()
            runCatching { windowManager?.removeView(it) }
        }
        pillView = null
        frameView = null
        renderer = null
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            service.resources.getDimensionPixelSize(id)
        } else {
            (24 * density).toInt()
        }
    }

    /** 全屏那层，只画边框。 */
    private inner class FrameView : View(service) {

        private var state: HudState? = null
        private var startedAt = 0L

        fun render(next: HudState) {
            state = next
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            schedule()
            invalidate()
        }

        fun start() {
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            schedule()
        }

        fun stop() {
            removeCallbacks(tick)
        }

        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                schedule()
            }
        }

        private fun schedule() {
            removeCallbacks(tick)
            val interval = renderer?.refreshIntervalMs() ?: return
            if (interval <= 0L) return
            postDelayed(tick, interval)
        }

        override fun onDraw(canvas: Canvas) {
            val painter = renderer ?: return
            val current = state ?: return
            painter.drawFrame(canvas, current, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    /** 胶囊那层，尺寸由渲染器决定。 */
    private inner class PillView : View(service) {

        private var state: HudState? = null
        private var startedAt = 0L

        fun render(next: HudState) {
            val relayout = next != state
            state = next
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            if (relayout) requestLayout()
            schedule()
            invalidate()
        }

        fun stop() {
            removeCallbacks(tick)
        }

        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                schedule()
            }
        }

        private fun schedule() {
            removeCallbacks(tick)
            val interval = renderer?.refreshIntervalMs() ?: return
            if (interval <= 0L) return
            postDelayed(tick, interval)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val painter = renderer
            val current = state
            val size = if (painter != null && current != null) {
                runCatching {
                    painter.measurePill(current, resources.displayMetrics.widthPixels)
                }.getOrNull()
            } else {
                null
            }
            applySize(size)
        }

        private fun applySize(size: HudSize?) {
            if (size == null || size.width <= 0 || size.height <= 0) {
                setMeasuredDimension(0, 0)
                return
            }
            setMeasuredDimension(size.width, size.height)
        }

        override fun onDraw(canvas: Canvas) {
            val painter = renderer ?: return
            val current = state ?: return
            painter.drawPill(canvas, current, SystemClock.elapsedRealtime() - startedAt)
        }
    }
}
