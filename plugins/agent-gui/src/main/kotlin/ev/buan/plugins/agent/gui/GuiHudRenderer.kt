package ev.buan.plugins.agent.gui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Typeface
import org.cordis.api.HudPulse
import org.cordis.api.HudRenderer
import org.cordis.api.HudSize
import org.cordis.api.HudState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 屏幕提示层的全部视觉：一颗状态胶囊 + 事件涟漪。
 *
 * 放在这个插件里，是因为它就是屏幕操作的一部分——"agent 正在动你的界面"这件事，和
 * "它能动界面"是同一件事的两面。
 *
 * **没有全屏跑马灯。** 边框上绕圈的光看着像"正在运行"，但它回答不了用户真正的问题：
 * 刚才动的是哪里。业界做法（AgentLens 的 spotlight、Siri 的辐射光）都是把光绑在**事件和位置**上，
 * 而不是让光自己在边框上走。所以这里只有两样东西：一条持续的状态，和每一次操作的落点。
 *
 * 时间算成时间的函数（[elapsedMs]），丢帧不会让相位漂移，任意一帧都能复现。
 */
class GuiHudRenderer : HudRenderer {

    private val density: Float = Resources.getSystem().displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * density
        color = 0xF2FFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ---------------------------------------------------------------- 胶囊

    override fun measurePill(state: HudState, maxWidthPx: Int): HudSize {
        val label = labelFor(state)
        val wanted = PADDING_DP * 2 * density + DOT_DP * density + GAP_DP * density +
            textPaint.measureText(label)
        val ceiling = min(MAX_WIDTH_DP * density, maxWidthPx - 32f * density)
        val width = max(wanted.coerceAtMost(max(ceiling, MIN_WIDTH_DP * density)), MIN_WIDTH_DP * density)
        return HudSize(width.toInt(), (HEIGHT_DP * density).toInt())
    }

    override fun drawPill(canvas: Canvas, state: HudState, elapsedMs: Long) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        fillPaint.color = PILL_BLACK
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, fillPaint)

        val dotRadius = DOT_DP * density / 2f
        val dotX = PADDING_DP * density + dotRadius
        val dotY = h / 2f
        val breath = (elapsedMs % BREATH_MS) / BREATH_MS.toFloat()
        val alpha = (DOT_MIN_ALPHA + (255 - DOT_MIN_ALPHA) * ((sin(breath * 2 * Math.PI) + 1) / 2)).toInt()
        dotPaint.color = Color.argb(
            alpha,
            Color.red(DOT_COLOR),
            Color.green(DOT_COLOR),
            Color.blue(DOT_COLOR),
        )
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

        val metrics = textPaint.fontMetrics
        val baseline = dotY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(labelFor(state), dotX + dotRadius + GAP_DP * density, baseline, textPaint)
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

    // ---------------------------------------------------------------- 涟漪

    /**
     * 一次操作的落点。
     *
     * 不是圆环。圆环（描边扩散）是水波纹 emoji 的做法，看起来廉价——真实的触摸反馈是
     * **一个边缘柔和的光点**：它出现时略微放大回弹，停一下，然后淡出。
     * 参数取自两处成熟实现：HeyGen 的 Touch Indicator（软点、44pt、出现 80ms）
     * 和 Screen Studio 的点击效果（白色、45% 不透明度、Zoom out 进入）。
     *
     * 边缘用径向渐变而不是硬边：硬边在任何背景上都是一块贴纸。
     */
    override fun drawPulses(canvas: Canvas, pulses: List<HudPulse>, elapsedMs: Long) {
        if (pulses.isEmpty()) return
        for (pulse in pulses) {
            val age = (elapsedMs - pulse.startedAt).coerceAtLeast(0L)
            if (age > PULSE_MS) continue
            val t = age / PULSE_MS.toFloat()

            val cx = if (pulse.width > 0) pulse.x + pulse.width / 2f else pulse.x.toFloat()
            val cy = if (pulse.height > 0) pulse.y + pulse.height / 2f else pulse.y.toFloat()

            // 出现时从 1.25 倍收到 1.0（回弹感），之后不再变。
            val scale = if (t < APPEAR_MS) {
                val p = t / APPEAR_MS
                1.25f - 0.25f * (1f - (1f - p) * (1f - p))
            } else {
                1f
            }
            // 透明度：快速起来 → 短暂保持 → 慢慢落下去。
            val fade = when {
                t < APPEAR_MS -> t / APPEAR_MS
                t < HOLD_END -> 1f
                else -> 1f - (t - HOLD_END) / (1f - HOLD_END)
            }.coerceIn(0f, 1f)
            if (fade <= 0f) continue

            val radius = PULSE_RADIUS_DP * density * scale

            // 阴影层：白点在浅色背景上会消失，先垫一层很淡的暗色扩散把它托住。
            shadowPaint.shader = RadialGradient(
                cx, cy, radius * 1.35f,
                intArrayOf(SHADOW_COLOR, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            shadowPaint.alpha = (fade * 255).toInt()
            canvas.drawCircle(cx, cy, radius * 1.35f, shadowPaint)

            // 光点本体：中心亮、0.55 处过半、边缘归零。
            dotPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(CORE_COLOR, BODY_COLOR, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            dotPaint.alpha = (fade * 255).toInt()
            canvas.drawCircle(cx, cy, radius, dotPaint)

            dotPaint.shader = null
            shadowPaint.shader = null
        }
    }

    /** 胶囊的呼吸点需要连续动画；没有涟漪时这个是唯一的重绘理由。 */
    override fun refreshIntervalMs(): Long = 16L

    private companion object {
        const val HEIGHT_DP = 38f
        const val MIN_WIDTH_DP = 104f
        const val MAX_WIDTH_DP = 300f
        const val PADDING_DP = 15f
        const val GAP_DP = 9f
        const val DOT_DP = 7f
        const val DOT_MIN_ALPHA = 90f
        const val BREATH_MS = 1600L

        /** 整个落点的寿命。420ms 是"看一眼就知道"，又不至于挡着下一次操作。 */
        const val PULSE_MS = 420L

        /** 出现阶段占整个寿命的比例：1.25 倍缩到 1 倍，和 Touch Indicator 的 80ms 对齐。 */
        const val APPEAR_MS = 0.19f

        /** 保持到 45% 处开始落下去，最后 55% 是淡出。 */
        const val HOLD_END = 0.45f

        /** 半径 22dp ≈ 直径 44dp，和触控热区的下限一致，看着像一根手指按的。 */
        const val PULSE_RADIUS_DP = 22f

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")

        /** 白色半透明：Screen Studio 的点击效果是白色 45%，这个更亮一点，因为屏幕上有干扰内容。 */
        val CORE_COLOR: Int = Color.parseColor("#B3FFFFFF")
        val BODY_COLOR: Int = Color.parseColor("#3DFFFFFF")
        val SHADOW_COLOR: Int = Color.parseColor("#26000000")
    }
}
