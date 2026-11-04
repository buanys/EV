package ev.buan.plugins.agent.gui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import org.cordis.api.HudRenderer
import org.cordis.api.HudSize
import org.cordis.api.HudState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 屏幕提示层的全部视觉：一圈边缘跑马灯 + 一颗胶囊。
 *
 * 放在这个插件里，是因为它就是屏幕操作的一部分——"agent 正在动你的界面"这件事，和
 * "它能动界面"是同一件事的两面。分出去只会让两边都缺一半。
 *
 * 宿主只提供窗口和画布，这里没有任何 Android 服务依赖：`Resources.getSystem()` 拿密度，
 * 其余全是 Canvas。
 *
 * 动画算成时间的函数（[elapsedMs]），不靠"上一帧加一点"：这样丢帧不会让相位漂移，也方便
 * 对着任意时间点复现某一帧。
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

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
    }

    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var shaderWidth = 0
    private var shaderHeight = 0

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

        rect.set(0f, 0f, w, h)
        val radius = h / 2f

        // 纯黑，没有渐变也没有描边：胶囊是一块实心黑，和背后是什么无关。
        fillPaint.color = PILL_BLACK
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

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

    // ---------------------------------------------------------------- 边框光

    override fun drawFrame(canvas: Canvas, state: HudState, elapsedMs: Long) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gradient = shaderFor(w, h) ?: return
        val phase = (elapsedMs % PERIOD_MS) / PERIOD_MS.toFloat() * 360f
        matrix.setRotate(phase, w / 2f, h / 2f)
        gradient.setLocalMatrix(matrix)

        // 两遍画同一圈：外圈柔光、内圈主体。只有一圈的话边缘是硬的，像贴了条胶带。
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
        val create = GLOW_COLOR
        val transparent = Color.argb(0, Color.red(create), Color.green(create), Color.blue(create))
        val solid = Color.argb(255, Color.red(create), Color.green(create), Color.blue(create))
        // 核心只占 0.10 圈，两侧各收一段渐变做拖尾；再宽就成了"整圈描边"，不像在跑。
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

    /** 跑马灯要连续，16ms 是上限；再快对一条光带没有意义。 */
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

        const val INSET_DP = 5f
        const val CORNER_DP = 30f
        const val GLOW_ALPHA = 0.10f
        const val CORE_ALPHA = 0.34f
        const val PERIOD_MS = 3600L

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")
        val GLOW_COLOR: Int = Color.parseColor("#7CD8FF")
    }
}
