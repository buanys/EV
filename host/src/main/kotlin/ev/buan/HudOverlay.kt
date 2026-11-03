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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import org.cordis.api.HudState

/**
 * 屏幕操作期间的可见性层：一圈边缘跑马灯 + 一颗进度胶囊。
 *
 * 为什么挂在无障碍服务上而不是普通悬浮窗：`TYPE_ACCESSIBILITY_OVERLAY` 由无障碍服务直接
 * 添加，不需要 `SYSTEM_ALERT_WINDOW`（那要用户再去设置里点一次），也不会被当成可点击窗口
 * 挡住别人——两个窗口都带 `FLAG_NOT_TOUCHABLE`，手指穿过去。
 *
 * 亮度是调出来的，不是随便给的：[MARQUEE_ALPHA] 0.3 在深色壁纸上刚好看得见、在浅色壁纸上
 * 不刺眼；胶囊用深玻璃而不是纯白，因为白色在全屏视频上跳得厉害。
 *
 * 所有绘制在 UI 线程，调用方（brain 的协程）可能在任何线程，所以 [show]/[hide] 都先切主线程。
 */
internal class HudOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)

    private var view: HudView? = null
    private var attached = false

    fun show(state: HudState) = onMain {
        val v = ensureView() ?: return@onMain
        v.render(state)
    }

    fun hide() = onMain { detach() }

    /** 服务销毁时调用：窗口必须自己拆，否则会留在屏幕上变成幽灵。 */
    fun dispose() = onMain { detach() }

    private fun onMain(action: () -> Unit) {
        runCatching { service.mainExecutor.execute(action) }
    }

    private fun ensureView(): HudView? {
        if (attached) view?.let { return it }
        val manager = windowManager ?: return null
        val created = HudView(service)
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
            title = "EV 屏幕操作提示"
            // 真·背景模糊：Android 12 起 LayoutParams 才认这个字段，而且要看系统有没有开
            // 跨窗口模糊（ColorOS 上 mBlurEnabled=true）。不支持就是一句 no-op，窗口退化成
            // 半透明——那正是 HUD 的兜底形态，所以不必先探测。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = (26 * service.resources.displayMetrics.density).toInt()
                }
            }
        }
        val added = runCatching { manager.addView(created, params) }
        if (added.isFailure) {
            // 静默失败是最糟的：用户以为在跑，其实什么都没有。
            android.util.Log.w("EV", "HUD 窗口挂不上去：${added.exceptionOrNull()?.message}")
            return null
        }
        view = created
        attached = true
        created.start()
        return created
    }

    private fun detach() {
        val current = view ?: return
        current.stop()
        runCatching { windowManager?.removeView(current) }
        view = null
        attached = false
    }
}

/**
 * 一整个屏幕大的透明视图，只画两样东西：边缘的光、顶部的胶囊。
 *
 * 一个窗口而不是两个：两层要同步出现/收起，分成两个窗口只会多一处可能不同步的地方。
 */
private class HudView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private var state: HudState? = null
    private var marqueePhase = 0f
    private var breathPhase = 0f

    private val marqueePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

    private val pillRect = RectF()
    private val glowRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MARQUEE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            marqueePhase = t * 360f
            // 呼吸比跑马灯快一倍多一点，两者不同步才不像同一个动画在重复。
            breathPhase = t
            invalidate()
        }
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
    }

    fun render(next: HudState) {
        val changed = next != state
        state = next
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = state ?: return
        drawMarquee(canvas)
        drawPill(canvas, current)
    }

    // ---------------------------------------------------------------- 跑马灯

    /**
     * 一条沿屏幕边缘绕圈的光。
     *
     * 用 `SweepGradient` + 旋转矩阵而不是四次 `LinearGradient`：四段独立渐变在圆角处会断，
     * 看起来像四条分开的线，一圈扫过去的光才像"正在运行"。
     */
    private fun drawMarquee(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val shader = shaderFor(w, h) ?: return
        shader.setLocalMatrix(Matrix().apply { setRotate(marqueePhase, w / 2f, h / 2f) })
        marqueePaint.shader = shader

        val inset = STROKE_DP * density / 2f
        glowRect.set(inset, inset, w - inset, h - inset)
        val radius = CORNER_DP * density
        canvas.drawRoundRect(glowRect, radius, radius, marqueePaint)
        marqueePaint.shader = null
    }

    private var cachedShader: SweepGradient? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    private fun shaderFor(w: Float, h: Float): SweepGradient? {
        val cached = cachedShader
        if (cached != null && cachedWidth == w.toInt() && cachedHeight == h.toInt()) return cached
        val glow = MARQUEE_COLOR
        val transparent = Color.argb(0, Color.red(glow), Color.green(glow), Color.blue(glow))
        val peak = Color.argb((MARQUEE_ALPHA * 255).toInt(), Color.red(glow), Color.green(glow), Color.blue(glow))
        // 光带只占整圈的一小段：0.06 圈约等于 20°，太长就成了整圈发光的边框。
        val shader = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(transparent, transparent, peak, transparent, transparent),
            floatArrayOf(0f, 0.66f, 0.72f, 0.78f, 1f),
        )
        cachedShader = shader
        cachedWidth = w.toInt()
        cachedHeight = h.toInt()
        return shader
    }

    // ---------------------------------------------------------------- 胶囊

    private fun drawPill(canvas: Canvas, current: HudState) {
        val label = labelFor(current)
        val textWidth = textPaint.measureText(label)
        val dotSize = DOT_DP * density
        val gap = GAP_DP * density
        val padding = PADDING_DP * density
        val heightPx = HEIGHT_DP * density

        val widthPx = (padding * 2 + dotSize + gap + textWidth)
            .coerceAtLeast(MIN_WIDTH_DP * density)
            .coerceAtMost(maxOf(MIN_WIDTH_DP * density, width - 32f * density))

        val top = statusBarHeight() + TOP_MARGIN_DP * density
        val left = (width - widthPx) / 2f
        pillRect.set(left, top, left + widthPx, top + heightPx)
        val radius = heightPx / 2f

        // 阴影先画：setShadowLayer 只影响随后那一次绘制。
        pillPaint.shader = null
        pillPaint.setShadowLayer(10f * density, 0f, 3f * density, 0x55000000)
        pillPaint.color = PILL_BASE
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
        pillPaint.clearShadowLayer()

        // 玻璃本体：上浅下深，模拟光从上面打下来的折射。
        pillPaint.shader = LinearGradient(
            pillRect.left, pillRect.top, pillRect.left, pillRect.bottom,
            PILL_TOP, PILL_BOTTOM, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
        pillPaint.shader = null

        // 顶部内高光：一条细窄的白色渐变，是"玻璃"和"纯色块"之间唯一的区别。
        val highlight = RectF(pillRect.left + radius * 0.6f, pillRect.top + 1f * density,
            pillRect.right - radius * 0.6f, pillRect.top + heightPx * 0.42f)
        pillPaint.shader = LinearGradient(
            highlight.left, highlight.top, highlight.left, highlight.bottom,
            0x38FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(highlight, radius * 0.7f, radius * 0.7f, pillPaint)
        pillPaint.shader = null

        // 外描边：上亮下暗，边缘才不会糊进背景。
        strokePaint.shader = LinearGradient(
            pillRect.left, pillRect.top, pillRect.left, pillRect.bottom,
            0x40FFFFFF, 0x0DFFFFFF, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(pillRect, radius, radius, strokePaint)
        strokePaint.shader = null

        // 呼吸圆点：位置固定，只变透明度。
        val dotCenterX = pillRect.left + padding + dotSize / 2f
        val dotCenterY = pillRect.centerY()
        val alpha = (DOT_MIN_ALPHA + (DOT_MAX_ALPHA - DOT_MIN_ALPHA) * ((kotlin.math.sin(breathPhase * 2 * Math.PI) + 1) / 2)).toInt()
        dotPaint.color = Color.argb(alpha, Color.red(DOT_COLOR), Color.green(DOT_COLOR), Color.blue(DOT_COLOR))
        canvas.drawCircle(dotCenterX, dotCenterY, dotSize / 2f, dotPaint)

        val metrics = textPaint.fontMetrics
        val baseline = pillRect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, dotCenterX + dotSize / 2f + gap, baseline, textPaint)
    }

    private fun labelFor(current: HudState): String {
        val subject = current.note ?: current.tool
        return when {
            current.step > 0 && subject != null -> "第 ${current.step} 步 · $subject"
            current.step > 0 -> "第 ${current.step} 步"
            subject != null -> subject
            else -> "正在思考"
        }
    }

    private var statusBarHeightCache = 0

    private fun statusBarHeight(): Int {
        if (statusBarHeightCache > 0) return statusBarHeightCache
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeightCache = if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else {
            (24 * density).toInt()
        }
        return statusBarHeightCache
    }

    private companion object {
        /** 光带宽度：比系统手势条粗一点，但远不到"边框"的程度。 */
        const val STROKE_DP = 2.2f
        const val CORNER_DP = 26f

        /** 峰值不透明度。0.3 在深色壁纸上清晰、在浅色上不刺眼。 */
        const val MARQUEE_ALPHA = 0.30f
        val MARQUEE_COLOR: Int = Color.parseColor("#7CD8FF")

        const val MARQUEE_PERIOD_MS = 3600L

        const val HEIGHT_DP = 38f
        const val MIN_WIDTH_DP = 104f
        const val PADDING_DP = 15f
        const val GAP_DP = 9f
        const val DOT_DP = 7f
        const val TOP_MARGIN_DP = 14f

        /** 玻璃底色：不是纯黑，带一点冷调，掠在视频上不会显得像一块补丁。 */
        val PILL_BASE: Int = Color.parseColor("#CC14181D")
        val PILL_TOP: Int = Color.parseColor("#E01F242B")
        val PILL_BOTTOM: Int = Color.parseColor("#CC101318")

        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")
        const val DOT_MIN_ALPHA = 90f
        const val DOT_MAX_ALPHA = 255f
    }
}
