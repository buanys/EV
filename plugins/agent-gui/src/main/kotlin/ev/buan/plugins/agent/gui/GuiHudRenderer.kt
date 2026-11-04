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
        textPaint.textSize = STEP_TEXT_DP * density
        subPaint.textSize = STATUS_TEXT_DP * density

        val content = max(
            textPaint.measureText(stepText),
            subPaint.measureText(state.status),
        )
        val fixed = (PADDING_DP * 2 + DOT_DP * 2 + DOT_GAP_DP * 2) * density
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
        fillPaint.color = PILL_BLACK
        canvas.drawRoundRect(pillRect, radius, radius, fillPaint)

        drawEdgeLight(canvas, w, h, radius, elapsedMs)

        val centerY = h / 2f
        val dotRadius = DOT_DP / 2f * density
        val leftX = PADDING_DP * density + dotRadius
        val rightX = w - PADDING_DP * density - dotRadius

        // 两颗纯色圆点，没有图标：颜色本身就是语义（黄=可暂停、绿=已暂停、红=停止）。
        // 画上暂停/播放/方块之后，它们看起来像从播放器上抠下来的按钮，廉价。
        val paused = state.phase == HudPhase.PAUSED
        val stopped = state.phase == HudPhase.STOPPED
        fillPaint.color = when {
            paused -> PAUSE_GREEN
            else -> PAUSE_YELLOW
        }
        canvas.drawCircle(leftX, centerY, dotRadius, fillPaint)

        fillPaint.color = if (stopped) STOP_MUTED else STOP_RED
        canvas.drawCircle(rightX, centerY, dotRadius, fillPaint)

        // 文字左对齐，贴着左点；居中的话两侧会留出两块不对称的空白，看起来像没对齐。
        val textX = leftX + dotRadius + DOT_GAP_DP * density
        textPaint.textSize = STEP_TEXT_DP * density
        textPaint.textAlign = Paint.Align.LEFT
        subPaint.textSize = STATUS_TEXT_DP * density
        subPaint.textAlign = Paint.Align.LEFT

        val stepBaseline = centerY - 1f * density
        canvas.drawText(stepLabel(state), textX, stepBaseline, textPaint)

        val statusBaseline = centerY + (STATUS_TEXT_DP + 3f) * density
        canvas.drawText(state.status, textX, statusBaseline, subPaint)
    }

    private fun stepLabel(state: HudState): String =
        if (state.step > 0) "第 ${state.step} 步" else "准备中"

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
            // 平方衰减：拖尾后段掉得比线性快，光带才有"头重脚轻"的方向感。
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
            // 点的半径也跟着收缩，光带两端才会收尖，而不是突然断掉。
            canvas.drawCircle(
                position[0],
                position[1],
                EDGE_DOT_RADIUS_DP * density * (0.4f + 0.6f * fade),
                rippleDotPaint,
            )
        }
    }

    override fun hitTest(x: Float, y: Float, state: HudState, size: HudSize): HudHit {
        val h = size.height.toFloat()
        val cy = h / 2f
        val dotRadius = DOT_DP / 2f * density
        val leftX = PADDING_DP * density + dotRadius
        val rightX = size.width - PADDING_DP * density - dotRadius
        // 触摸热区比圆点大得多：12dp 的点用手点太苛刻，28dp 才接近系统的最小触控目标。
        val reach = TOUCH_TARGET_DP / 2f * density

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
        /**
         * 44dp 高。56dp 是上一版——两颗 24dp 的大按钮把它撑得像一条播放器控件；
         * 去掉图标、按钮收到 12dp 之后，两行文字 44dp 就够。
         */
        const val HEIGHT_DP = 44f
        const val MIN_WIDTH_DP = 168f
        const val MAX_WIDTH_DP = 280f
        const val PADDING_DP = 14f

        /** 纯色圆点，没有图标。颜色本身承担语义。 */
        const val DOT_DP = 12f
        const val DOT_GAP_DP = 11f

        /** 触摸热区 32dp：圆点小，手指不小。 */
        const val TOUCH_TARGET_DP = 32f

        const val STEP_TEXT_DP = 13f
        const val STATUS_TEXT_DP = 11f

        /** 边缘光：一圈 3.4 秒演变成 16 个渐隐点，比 12 点更连续。 */
        const val EDGE_PERIOD_MS = 3400L
        const val EDGE_DOTS = 16
        const val EDGE_DOT_RADIUS_DP = 2.0f
        const val EDGE_DOT_SPACING_DP = 2.6f
        const val EDGE_ALPHA = 1.0f
        const val EDGE_WIDTH_DP = 1.2f

        const val PULSE_MS = 420L
        const val APPEAR_MS = 0.19f
        const val HOLD_END = 0.45f
        const val PULSE_RADIUS_DP = 22f

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val EDGE_COLOR: Int = Color.parseColor("#4DA6FF")

        /**
         * 三个颜色都往低饱和压过一档。M3 Expressive 讲的是 tonal palette——纯 #FFC53D /
         * #FF5A5A 在深色胶囊上一片高饱和，扎眼且廉价；压 20% 之后仍然是同一种语义，
         * 但看起来像设计过，不是调色板里直接取出来的。
         */
        val PAUSE_YELLOW: Int = Color.parseColor("#D9AC4A")
        val PAUSE_GREEN: Int = Color.parseColor("#4FB477")
        val STOP_RED: Int = Color.parseColor("#C96A62")
        val STOP_MUTED: Int = Color.parseColor("#4A4A4A")

        val CORE_COLOR: Int = Color.parseColor("#B3FFFFFF")
        val BODY_COLOR: Int = Color.parseColor("#3DFFFFFF")
        val SHADOW_COLOR: Int = Color.parseColor("#26000000")
    }
}
