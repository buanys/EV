package ev.buan

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import org.cordis.api.HudState

/**
 * 屏幕操作期间的可见性层：一圈边缘跑马灯 + 一颗进度胶囊。
 *
 * **两个窗口，不是一个。** 一开始做成一个全屏窗口，结果 `FLAG_BLUR_BEHIND` 把整块屏幕
 * 背后都糊了——模糊是加在**窗口矩形**上的，跟画了什么无关。要的只是胶囊那块玻璃，所以：
 *
 *  - 跑马灯：全屏、透明、**不带模糊**；
 *  - 胶囊：`WRAP_CONTENT` 的小窗口，模糊半径只覆盖它自己，那才是液态玻璃。
 *
 * 两个都用 `TYPE_ACCESSIBILITY_OVERLAY`：无障碍服务直接添加，不需要 `SYSTEM_ALERT_WINDOW`。
 * 都带 `FLAG_NOT_TOUCHABLE`，手指穿过去。
 */
internal class HudOverlay(private val service: AccessibilityService) {

    private val density = service.resources.displayMetrics.density
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)

    private var marquee: MarqueeView? = null
    private var pill: PillView? = null

    fun show(state: HudState) = onMain {
        val target = ensurePill()
        if (target == null) {
            android.util.Log.w("EV", "HUD show 放弃：胶囊窗口没挂上")
            return@onMain
        }
        ensureMarquee()
        target.render(state)
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
        pill?.let { return it }
        val manager = windowManager ?: return null
        val created = PillView(service)
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
            // gravity 里的 TOP + 这个偏移，比在 View 里留空白更准：窗口从状态栏下沿开始，
            // 模糊区域也正好从那里开始。
            y = statusBarHeight() + (14 * density).toInt()
            title = "EV 屏幕操作提示"
            // 不做玻璃了：真背景模糊要系统在窗口层做，而在无障碍 overlay 上它要么糊整屏、
            // 要么边缘溢出，两个都比纯黑难看。纯黑没有透光问题，在浅色和深色壁纸上都稳。
        }
        val added = runCatching { manager.addView(created, params) }
        if (added.isFailure) {
            android.util.Log.w("EV", "HUD 胶囊窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        pill = created
        android.util.Log.i("EV", "HUD 胶囊窗口已挂上")
        return created
    }

    // ---------------------------------------------------------------- 跑马灯

    private fun ensureMarquee(): MarqueeView? {
        marquee?.let { return it }
        val manager = windowManager ?: return null
        val created = MarqueeView(service)
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
            // 这里**故意不加** FLAG_BLUR_BEHIND。
        }
        val added = runCatching { manager.addView(created, params) }
        if (added.isFailure) {
            android.util.Log.w("EV", "HUD 跑马灯窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        marquee = created
        created.start()
        android.util.Log.i("EV", "HUD 跑马灯窗口已挂上")
        return created
    }

    private fun detachAll() {
        pill?.let { runCatching { windowManager?.removeView(it) } }
        marquee?.let {
            it.stop()
            runCatching { windowManager?.removeView(it) }
        }
        pill = null
        marquee = null
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            service.resources.getDimensionPixelSize(id)
        } else {
            (24 * density).toInt()
        }
    }
}

/**
 * 边缘的一圈光。
 *
 * 比第一版做的三件事：
 *  - 光带从 2.2dp 加到 3.2dp，并**往里缩 5dp**：贴物理边缘看起来像屏幕漏光，不像"正在操作"；
 *  - 加一层 10dp 的柔光垫底，光带不再是硬边的一条线；
 *  - 圆角跟着屏幕走，底部和顶部一样能看见。
 */
private class MarqueeView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLOW_DP * density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CORE_DP * density
    }

    private val rect = RectF()
    private val matrix = Matrix()
    private var phase = 0f
    private var shader: SweepGradient? = null
    private var shaderWidth = 0
    private var shaderHeight = 0

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gradient = shaderFor(w, h) ?: return
        matrix.setRotate(phase, w / 2f, h / 2f)
        gradient.setLocalMatrix(matrix)

        // 两遍画同一圈：外圈是柔光，内圈是主体。只有一圈的话边缘是硬的，像贴了条胶带。
        val inset = INSET_DP * density
        rect.set(inset, inset, w - inset, h - inset)
        val radius = CORNER_DP * density

        glowPaint.shader = gradient
        glowPaint.alpha = (GLOW_ALPHA * 255).toInt()
        canvas.drawRoundRect(rect, radius, radius, glowPaint)

        corePaint.shader = gradient
        corePaint.alpha = (CORE_ALPHA * 255).toInt()
        canvas.drawRoundRect(rect, radius, radius, corePaint)

        glowPaint.shader = null
        corePaint.shader = null
    }

    private fun shaderFor(w: Float, h: Float): SweepGradient? {
        if (shaderWidth == w.toInt() && shaderHeight == h.toInt()) return shader
        val glow = GLOW_COLOR
        val transparent = Color.argb(0, Color.red(glow), Color.green(glow), Color.blue(glow))
        val solid = Color.argb(255, Color.red(glow), Color.green(glow), Color.blue(glow))
        // 光带占整圈的核心只有 0.05，再往两侧各收一段渐变做拖尾。
        val created = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(transparent, transparent, solid, transparent, transparent),
            floatArrayOf(0f, 0.62f, 0.72f, 0.86f, 1f),
        )
        shader = created
        shaderWidth = w.toInt()
        shaderHeight = h.toInt()
        return created
    }

    private companion object {
        const val GLOW_DP = 10f
        const val CORE_DP = 3.2f
        const val INSET_DP = 5f
        const val CORNER_DP = 30f
        const val GLOW_ALPHA = 0.10f
        const val CORE_ALPHA = 0.34f
        const val PERIOD_MS = 3600L
        val GLOW_COLOR: Int = Color.parseColor("#7CD8FF")
    }
}

/**
 * 进度胶囊。
 *
 * 窗口就是胶囊本身（`WRAP_CONTENT`），所以系统给的背景模糊正好落在玻璃上，不会波及全屏。
 */
private class PillView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private var label = "正在思考"
    private var breath = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * density
        color = 0xF2FFFFFF.toInt()
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
    }

    fun render(state: HudState) {
        val next = labelFor(state)
        if (next != label) {
            label = next
            requestLayout()
        }
        if (!animator.isStarted) animator.start()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = textPaint.measureText(label)
        val wanted = PADDING_DP * 2 * density + DOT_DP * density + GAP_DP * density + textWidth
        val width = wanted.coerceIn(MIN_WIDTH_DP * density, MAX_WIDTH_DP * density).toInt()
        setMeasuredDimension(width, (HEIGHT_DP * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        val radius = h / 2f

        // 纯黑，不带渐变也不带描边：胶囊是一块实心黑，和背后是什么无关。
        fillPaint.color = PILL_BLACK
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // 呼吸圆点。
        val dotRadius = DOT_DP * density / 2f
        val dotX = PADDING_DP * density + dotRadius
        val dotY = h / 2f
        val alpha = (DOT_MIN_ALPHA + (255 - DOT_MIN_ALPHA) * ((kotlin.math.sin(breath * 2 * Math.PI) + 1) / 2)).toInt()
        dotPaint.color = Color.argb(alpha, Color.red(DOT_COLOR), Color.green(DOT_COLOR), Color.blue(DOT_COLOR))
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

        val metrics = textPaint.fontMetrics
        val baseline = dotY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, dotX + dotRadius + GAP_DP * density, baseline, textPaint)
    }

    private fun labelFor(state: HudState): String {
        val subject = state.note ?: state.tool
        return when {
            state.step > 0 && subject != null -> "第 ${state.step} 步 · $subject"
            state.step > 0 -> "第 ${state.step} 步"
            subject != null -> subject
            else -> "正在思考"
        }
    }

    private companion object {
        const val HEIGHT_DP = 38f
        const val MIN_WIDTH_DP = 104f
        const val MAX_WIDTH_DP = 300f
        const val PADDING_DP = 15f
        const val GAP_DP = 9f
        const val DOT_DP = 7f
        const val DOT_MIN_ALPHA = 90f

        /** 纯黑。不做玻璃也就不需要"透光"，黑在两种壁纸上都成立。 */
        val PILL_BLACK: Int = Color.parseColor("#FF000000")

        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")
    }
}
