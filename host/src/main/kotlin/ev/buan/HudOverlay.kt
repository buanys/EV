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
import org.cordis.api.HudPulse
import org.cordis.api.HudRenderer
import org.cordis.api.HudSize
import org.cordis.api.HudState

/**
 * 提示层的**窗口**部分：挂窗口、摆位置、把画布递给 [HudRenderer]。
 *
 * 这里一行绘制代码都没有——画什么归插件（agent-gui）。宿主只负责插件搬不动的事：
 * `TYPE_ACCESSIBILITY_OVERLAY` 只有 `AccessibilityService` 能加。
 *
 * 两个窗口：
 *  - 胶囊：`WRAP_CONTENT`，尺寸由渲染器算，只放一颗胶囊；
 *  - 涟漪：全屏、透明，放过场的事件反馈。
 */
internal class HudOverlay(private val service: AccessibilityService) {

    private val density = service.resources.displayMetrics.density
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)

    private var pulseView: PulseView? = null
    private var pillView: PillView? = null
    private var renderer: HudRenderer? = null

    fun show(state: HudState, painter: HudRenderer?) = onMain {
        if (painter == null) {
            detachAll()
            return@onMain
        }
        renderer = painter
        val pill = ensurePill() ?: return@onMain
        pill.render(state)
    }

    /** 一次操作刚发生。涟漪和胶囊互不干扰，所以这里不碰 [show] 的状态。 */
    fun pulse(pulse: HudPulse, painter: HudRenderer?) = onMain {
        // 正常情况下 brain 已经在工具调用前 show 过一次，renderer 就绪；
        // 但工具也可能在没有胶囊的情况下单独打涟漪，这时候用它带上来的那个。
        val active = renderer ?: painter ?: return@onMain
        if (renderer == null) renderer = active
        val frame = ensurePulseLayer(active) ?: return@onMain
        frame.add(pulse)
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

    // ---------------------------------------------------------------- 涟漪

    private fun ensurePulseLayer(painter: HudRenderer): PulseView? {
        pulseView?.let { return it }
        val manager = windowManager ?: return null
        val created = PulseView()
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
            title = "EV 屏幕操作涟漪"
            // FLAG_LAYOUT_NO_LIMITS 只允许窗口超出屏幕，**不阻止**系统按 inset 裁剪：
            // 默认 fitTypes 含状态栏和导航栏，窗口会从 y=140 开始，屏幕顶部的涟漪就画不到。
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
            android.util.Log.w("EV", "HUD 涟漪窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        android.util.Log.i("EV", "HUD 涟漪层已挂上（${painter.javaClass.simpleName}）")
        pulseView = created
        return created
    }

    private fun detachAll() {
        pillView?.let { runCatching { windowManager?.removeView(it) } }
        pulseView?.let {
            it.stop()
            runCatching { windowManager?.removeView(it) }
        }
        pillView = null
        pulseView = null
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

    /** 全屏那层：只画涟漪。 */
    private inner class PulseView : View(service) {

        private val pulses = mutableListOf<HudPulse>()

        fun add(pulse: HudPulse) {
            pulses.add(pulse)
            schedule()
            invalidate()
        }

        fun stop() {
            removeCallbacks(tick)
            pulses.clear()
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
            val now = SystemClock.elapsedRealtime()
            // 过期的丢掉：涟漪是有限寿命的，没人清理就会一直画下去。
            pulses.removeAll { now - it.startedAt > PULSE_LIFETIME_MS }
            if (pulses.isEmpty()) return
            painter.drawPulses(canvas, pulses.toList(), now)
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
            val size: HudSize? = if (painter != null && current != null) {
                runCatching {
                    painter.measurePill(current, resources.displayMetrics.widthPixels)
                }.getOrNull()
            } else {
                null
            }
            if (size == null || size.width <= 0 || size.height <= 0) {
                setMeasuredDimension(0, 0)
            } else {
                setMeasuredDimension(size.width, size.height)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val painter = renderer ?: return
            val current = state ?: return
            painter.drawPill(canvas, current, SystemClock.elapsedRealtime() - startedAt)
        }
    }
}

/**
 * 涟漪活多久。900ms 比动画本身长，留出余量：渲染器自己按年龄算透明度，
 * 宿主只负责在明显过期后把对象丢掉。
 */
private const val PULSE_LIFETIME_MS = 900L
