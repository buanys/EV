package ev.buan.plugins.agent.gui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
     * 一次操作的落点：一圈从原点扩散、逐渐变细变淡的环，加一个很快消失的实心点。
     *
     * 环是"我动了这里"，实心点是"落点"。两者一起才读得出方向——只有环像涟漪，
     * 只有点像笔迹，叠在一起才像"手指在这里按了一下"。
     */
    override fun drawPulses(canvas: Canvas, pulses: List<HudPulse>, elapsedMs: Long) {
        if (pulses.isEmpty()) return
        for (pulse in pulses) {
            val age = (elapsedMs - pulse.startedAt).coerceAtLeast(0L)
            if (age > PULSE_MS) continue
            val progress = age / PULSE_MS.toFloat()
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)

            val cx = if (pulse.width > 0) pulse.x + pulse.width / 2f else pulse.x.toFloat()
            val cy = if (pulse.height > 0) pulse.y + pulse.height / 2f else pulse.y.toFloat()

            // 有目标的矩形就描个框：知道自己点的是什么，比只知道点了哪里更有用。
            if (pulse.width > 0 && pulse.height > 0) {
                val boxProgress = (progress / 0.5f).coerceAtMost(1f)
                val boxAlpha = ((1f - boxProgress) * SPOT_ALPHA * 255).toInt()
                if (boxAlpha > 0) {
                    ripplePaint.color = Color.argb(
                        boxAlpha,
                        Color.red(PULSE_COLOR),
                        Color.green(PULSE_COLOR),
                        Color.blue(PULSE_COLOR),
                    )
                    ripplePaint.strokeWidth = SPOT_STROKE_DP * density
                    val half = SPOT_PADDING_DP * density
                    val left = pulse.x - half
                    val top = pulse.y - half
                    val right = pulse.x + pulse.width + half
                    val bottom = pulse.y + pulse.height + half
                    canvas.drawRoundRect(left, top, right, bottom, half, half, ripplePaint)
                }
            }

            val ringRadius = RIPPLE_MIN_DP * density + (RIPPLE_MAX_DP - RIPPLE_MIN_DP) * density * eased
            val ringAlpha = ((1f - progress) * RIPPLE_ALPHA * 255).toInt()
            if (ringAlpha > 0) {
                ripplePaint.color = Color.argb(
                    ringAlpha,
                    Color.red(PULSE_COLOR),
                    Color.green(PULSE_COLOR),
                    Color.blue(PULSE_COLOR),
                )
                ripplePaint.strokeWidth = (RIPPLE_WIDTH_DP * (1f - progress * 0.6f)) * density
                canvas.drawCircle(cx, cy, ringRadius, ripplePaint)
            }

            // 落点：前 35% 的时间里从 6dp 收到 2dp，然后消失。
            val dotProgress = (progress / 0.35f).coerceAtMost(1f)
            if (dotProgress < 1f) {
                spotPaint.color = Color.argb(
                    ((1f - dotProgress) * 220).toInt(),
                    Color.red(PULSE_COLOR),
                    Color.green(PULSE_COLOR),
                    Color.blue(PULSE_COLOR),
                )
                canvas.drawCircle(cx, cy, (DOT_START_DP - DOT_END_DP * dotProgress) * density, spotPaint)
            }
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

        /** 涟漪总时长。600ms 足够看完一圈扩散，又不至于等下一个事件时还挂在那里。 */
        const val PULSE_MS = 600L

        const val RIPPLE_MIN_DP = 6f
        const val RIPPLE_MAX_DP = 46f
        const val RIPPLE_WIDTH_DP = 2.5f
        const val RIPPLE_ALPHA = 0.55f

        const val DOT_START_DP = 6f
        const val DOT_END_DP = 2f

        const val SPOT_STROKE_DP = 1.6f
        const val SPOT_PADDING_DP = 6f
        const val SPOT_ALPHA = 0.45f

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")

        /** 涟漪的颜色：和胶囊呼吸点同源，一整屏只有一种冷色在动，不花。 */
        val PULSE_COLOR: Int = Color.parseColor("#8FE3FF")
    }
}
