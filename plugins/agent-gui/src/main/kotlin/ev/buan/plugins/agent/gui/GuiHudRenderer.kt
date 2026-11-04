package ev.buan.plugins.agent.gui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Typeface
import org.cordis.api.HudHit
import org.cordis.api.HudPhase
import org.cordis.api.HudPulse
import org.cordis.api.HudRenderer
import org.cordis.api.HudSize
import org.cordis.api.HudState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 屏幕提示层的全部视觉：一颗可操作的胶囊 + 事件落点。
 *
 * 胶囊结构（按钮在**内部**左右两侧，中间上下两行）：
 *
 * ```
 * ┌──────────────────────────────┐
 * │  ●    第 3 步            ●   │   ← 外圈一条蓝色光带在绕
 * │       操作中                 │
 * └──────────────────────────────┘
 *    🟡                     🔴
 * ```
 *
 * 左键暂停（暂停后变绿），右键停止。交互的位置由 [hitTest] 给出——宿主只转发坐标，
 * 不猜按钮在哪。
 *
 * 没有全屏跑马灯。边框上绕圈的光回答不了"刚才动的是哪里"，那是事件落点的事。
 */
class GuiHudRenderer : HudRenderer {

    private val density: Float = Resources.getSystem().displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = 0xF2FFFFFF.toInt()
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = 0x99FFFFFF.toInt()
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rippleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rippleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pillRect = RectF()
    private val pillPath = Path()
    private val measure = PathMeasure()
    private var pathWidth = 0
    private var pathHeight = 0

    // ---------------------------------------------------------------- 胶囊

    override fun measurePill(state: HudState, maxWidthPx: Int): HudSize {
        val stepText = stepLabel(state)
        val statusText = state.status
        textPaint.textSize = STEP_TEXT_DP * density
        subPaint.textSize = STATUS_TEXT_DP * density

        val content = max(
            textPaint.measureText(stepText),
            subPaint.measureText(statusText),
        )
        val fixed = (PADDING_DP * 2 + BUTTON_DP * 2 + BUTTON_GAP_DP * 2) * density
        val wanted = fixed + content
        val ceiling = min(MAX_WIDTH_DP * density, maxWidthPx - 32f * density)
        val width = wanted.coerceIn(MIN_WIDTH_DP * density, max(ceiling, MIN_WIDTH_DP * density))
        return HudSize(width.toInt(), (HEIGHT_DP * density).toInt())
    }

    override fun drawPill(canvas: Canvas, state: HudState, elapsedMs: Long) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        pillRect.set(0f, 0f, w, h)

        // 底：纯黑。不做玻璃，胶囊是一块实心黑，和背后是什么无关。
        fillPaint.color = PILL_BLACK
        canvas.drawRoundRect(pillRect, radius, radius, fillPaint)

        drawEdgeLight(canvas, w, h, radius, elapsedMs)

        val buttonY = h / 2f
        val leftX = (PADDING_DP + BUTTON_DP / 2f) * density
        val rightX = w - (PADDING_DP + BUTTON_DP / 2f) * density

        // 左键：运行中是黄，暂停后变绿。点击它是"暂停 ⇄ 继续"。
        val paused = state.phase == HudPhase.PAUSED
        drawButton(canvas, leftX, buttonY, if (paused) PAUSE_TO_GREEN else PAUSE_YELLOW, paused)
        // 右键：停止。已停止时画成灰的，表示已经停了。
        val stopped = state.phase == HudPhase.STOPPED
        drawButton(canvas, rightX, buttonY, if (stopped) STOP_DISABLED else STOP_RED, true)

        // 中间两行：上一步骤，下状态。
        val textLeft = leftX + (BUTTON_DP / 2f + BUTTON_GAP_DP) * density
        val textRight = rightX - (BUTTON_DP / 2f + BUTTON_GAP_DP) * density
        val centerX = (textLeft + textRight) / 2f

        textPaint.textSize = STEP_TEXT_DP * density
        textPaint.textAlign = Paint.Align.CENTER
        subPaint.textSize = STATUS_TEXT_DP * density
        subPaint.textAlign = Paint.Align.CENTER

        val stepBaseline = h / 2f - 1.5f * density
        canvas.drawText(stepLabel(state), centerX, stepBaseline, textPaint)

        val statusBaseline = h / 2f + (STATUS_TEXT_DP + 3f) * density
        canvas.drawText(state.status, centerX, statusBaseline, subPaint)
    }

    private fun stepLabel(state: HudState): String =
        if (state.step > 0) "第 ${state.step} 步" else "准备中"

    /**
     * 按钮：一个实心圆 + 白色小图标。
     *
     * 暂停时是绿色、图标是三角（继续）；运行时是黄色、图标是两条竖线（暂停）。
     * 停止永远是方块。
     */
    private fun drawButton(canvas: Canvas, cx: Float, cy: Float, color: Int, drawGlyph: Boolean) {
        val radius = BUTTON_DP / 2f * density
        buttonPaint.color = color
        canvas.drawCircle(cx, cy, radius, buttonPaint)
        if (!drawGlyph) return

        val glyph = radius * 0.42f
        val pausedStyle = color == PAUSE_TO_GREEN
        when {
            color == STOP_RED || color == STOP_DISABLED -> {
                // 方块
                canvas.drawRoundRect(
                    cx - glyph, cy - glyph, cx + glyph, cy + glyph,
                    glyph * 0.25f, glyph * 0.25f, glyphPaint,
                )
            }

            pausedStyle -> {
                // 三角（继续）
                val path = Path().apply {
                    moveTo(cx - glyph * 0.7f, cy - glyph)
                    lineTo(cx + glyph * 0.9f, cy)
                    lineTo(cx - glyph * 0.7f, cy + glyph)
                    close()
                }
                canvas.drawPath(path, glyphPaint)
            }

            else -> {
                // 两条竖线（暂停）
                val barWidth = glyph * 0.5f
                canvas.drawRoundRect(
                    cx - glyph * 0.75f, cy - glyph, cx - glyph * 0.75f + barWidth, cy + glyph,
                    barWidth * 0.4f, barWidth * 0.4f, glyphPaint,
                )
                canvas.drawRoundRect(
                    cx + glyph * 0.25f, cy - glyph, cx + glyph * 0.25f + barWidth, cy + glyph,
                    barWidth * 0.4f, barWidth * 0.4f, glyphPaint,
                )
            }
        }
    }

    /**
     * 边缘的蓝色光带。
     *
     * 用 [PathMeasure] 沿胶囊的实际轮廓取点，而不是 `SweepGradient`：胶囊长宽比接近 5:1，
     * 角度渐变会让光在两端跑得飞快、在上下边慢得发闷。沿路径取点才是匀速的。
     *
     * 光带由一串渐隐的圆点组成——单点像光标，连线像描边，只有渐隐的点串才像"光在流"。
     */
    private fun drawEdgeLight(canvas: Canvas, w: Float, h: Float, radius: Float, elapsedMs: Long) {
        if (w.toInt() != pathWidth || h.toInt() != pathHeight) {
            pillPath.reset()
            val inset = EDGE_WIDTH_DP * density / 2f
            pillPath.addRoundRect(
                inset, inset, w - inset, h - inset,
                radius - inset, radius - inset,
                Path.Direction.CW,
            )
            measure.setPath(pillPath, false)
            pathWidth = w.toInt()
            pathHeight = h.toInt()
        }
        val length = measure.length
        if (length <= 0f) return

        val head = (elapsedMs % EDGE_PERIOD_MS) / EDGE_PERIOD_MS.toFloat() * length
        val position = FloatArray(2)
        for (i in 0 until EDGE_DOTS) {
            val fade = 1f - i / EDGE_DOTS.toFloat()
            val alpha = (fade * fade * EDGE_ALPHA * 255).toInt()
            if (alpha <= 0) continue
            val distance = (head - i * EDGE_DOT_SPACING_DP * density + length) % length
            measure.getPosTan(distance, position, null)
            rippleDotPaint.color = Color.argb(
                alpha,
                Color.red(EDGE_COLOR),
                Color.green(EDGE_COLOR),
                Color.blue(EDGE_COLOR),
            )
            canvas.drawCircle(position[0], position[1], EDGE_DOT_RADIUS_DP * density * fade, rippleDotPaint)
        }
    }

    override fun hitTest(x: Float, y: Float, state: HudState, size: HudSize): HudHit {
        val h = size.height.toFloat()
        // 触摸热区比按钮本身大一圈：20dp 的圆点用手点太苛刻。
        val reach = (BUTTON_DP / 2f + 8f) * density
        val cy = h / 2f
        val leftX = (PADDING_DP + BUTTON_DP / 2f) * density
        val rightX = size.width - (PADDING_DP + BUTTON_DP / 2f) * density

        if (distance(x, y, leftX, cy) <= reach) return HudHit.PAUSE_RESUME
        if (distance(x, y, rightX, cy) <= reach) return HudHit.STOP
        return HudHit.NONE
    }

    private fun distance(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ---------------------------------------------------------------- 落点

    /**
     * 一次操作的落点。
     *
     * 不是圆环。圆环（描边扩散）是水波纹 emoji 的做法，看起来廉价——真实的触摸反馈是
     * **一个边缘柔和的光点**：它出现时略微放大回弹，停一下，然后淡出。
     * 参数取自两处成熟实现：HeyGen 的 Touch Indicator（软点、44pt、出现 80ms）
     * 和 Screen Studio 的点击效果（白色、45% 不透明度、Zoom out 进入）。
     */
    override fun drawPulses(canvas: Canvas, pulses: List<HudPulse>, elapsedMs: Long) {
        if (pulses.isEmpty()) return
        for (pulse in pulses) {
            val age = (elapsedMs - pulse.startedAt).coerceAtLeast(0L)
            if (age > PULSE_MS) continue
            val t = age / PULSE_MS.toFloat()

            val cx = if (pulse.width > 0) pulse.x + pulse.width / 2f else pulse.x.toFloat()
            val cy = if (pulse.height > 0) pulse.y + pulse.height / 2f else pulse.y.toFloat()

            val scale = if (t < APPEAR_MS) {
                val p = t / APPEAR_MS
                1.25f - 0.25f * (1f - (1f - p) * (1f - p))
            } else {
                1f
            }
            val fade = when {
                t < APPEAR_MS -> t / APPEAR_MS
                t < HOLD_END -> 1f
                else -> 1f - (t - HOLD_END) / (1f - HOLD_END)
            }.coerceIn(0f, 1f)
            if (fade <= 0f) continue

            val radius = PULSE_RADIUS_DP * density * scale

            rippleShadowPaint.shader = RadialGradient(
                cx, cy, radius * 1.35f,
                intArrayOf(SHADOW_COLOR, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            rippleShadowPaint.alpha = (fade * 255).toInt()
            canvas.drawCircle(cx, cy, radius * 1.35f, rippleShadowPaint)

            rippleDotPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(CORE_COLOR, BODY_COLOR, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            rippleDotPaint.alpha = (fade * 255).toInt()
            canvas.drawCircle(cx, cy, radius, rippleDotPaint)

            rippleDotPaint.shader = null
            rippleShadowPaint.shader = null
        }
    }

    override fun refreshIntervalMs(): Long = 16L

    private companion object {
        /** 两行文字撑高到 56dp：38dp 装不下"第 N 步"加状态词。 */
        const val HEIGHT_DP = 56f
        const val MIN_WIDTH_DP = 196f
        const val MAX_WIDTH_DP = 300f
        const val PADDING_DP = 12f
        const val BUTTON_DP = 24f
        const val BUTTON_GAP_DP = 8f
        const val STEP_TEXT_DP = 13f
        const val STATUS_TEXT_DP = 11f

        /** 边缘光：一圈 3.4 秒，12 个渐隐的点串成一条带。 */
        const val EDGE_PERIOD_MS = 3400L
        const val EDGE_DOTS = 12
        const val EDGE_DOT_RADIUS_DP = 1.6f
        const val EDGE_DOT_SPACING_DP = 2.4f
        const val EDGE_ALPHA = 0.85f
        const val EDGE_WIDTH_DP = 1.2f

        const val PULSE_MS = 420L
        const val APPEAR_MS = 0.19f
        const val HOLD_END = 0.45f
        const val PULSE_RADIUS_DP = 22f

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val EDGE_COLOR: Int = Color.parseColor("#4DA6FF")

        val PAUSE_YELLOW: Int = Color.parseColor("#FFC53D")
        val PAUSE_TO_GREEN: Int = Color.parseColor("#3DDC84")
        val STOP_RED: Int = Color.parseColor("#FF5A5A")
        val STOP_DISABLED: Int = Color.parseColor("#5A5A5A")

        val CORE_COLOR: Int = Color.parseColor("#B3FFFFFF")
        val BODY_COLOR: Int = Color.parseColor("#3DFFFFFF")
        val SHADOW_COLOR: Int = Color.parseColor("#26000000")
    }
}
