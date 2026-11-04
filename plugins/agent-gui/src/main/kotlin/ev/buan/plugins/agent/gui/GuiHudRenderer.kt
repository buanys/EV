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

    /** 边缘辉光的一层：多宽、多亮。 */
    private data class EdgeLayer(val widthDp: Float, val alpha: Float)

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

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

        // 六层同心的描边叠成一道辉光：外层宽而极淡，越往里越窄越亮。
        // 一条固定宽度的线不管多粗都是"线"；有厚度的是这种从边缘向内衰减的过渡。
        // 最外面那层看不见，但它决定了整圈光的"底子"，去掉之后内侧几层会显得生硬。
        val inset = INSET_DP * density
        rect.set(inset, inset, w - inset, h - inset)
        val radius = CORNER_DP * density

        edgePaint.shader = gradient
        for (layer in EDGE_LAYERS) {
            edgePaint.strokeWidth = layer.widthDp * density
            edgePaint.alpha = (layer.alpha * 255).toInt()
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
        }
        edgePaint.shader = null
        edgePaint.alpha = 255
    }

    private fun shaderFor(w: Float, h: Float): SweepGradient? {
        if (shaderWidth == w.toInt() && shaderHeight == h.toInt()) return shader
        // 彩虹光带：中间一段是七色，两侧留白。整圈都上色就成了 RGB 灯条，边缘一圈都在响，
        // 正是"遮蔽内容"的那种亮。所以只让一小段有颜色，其余全透明。
        val colors = intArrayOf(
            Color.TRANSPARENT, Color.TRANSPARENT,
            RAINBOW[0], RAINBOW[1], RAINBOW[2], RAINBOW[3], RAINBOW[4], RAINBOW[5], RAINBOW[6],
            Color.TRANSPARENT, Color.TRANSPARENT,
        )
        val positions = floatArrayOf(
            0f, BAND_START,
            BAND_START + 0.06f, BAND_START + 0.10f, BAND_START + 0.14f, BAND_START + 0.18f,
            BAND_START + 0.22f, BAND_START + 0.26f, BAND_START + 0.30f,
            BAND_END, 1f,
        )
        val created = SweepGradient(w / 2f, h / 2f, colors, positions)
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

        const val INSET_DP = 15f
        const val CORNER_DP = 30f

        /**
         * 六层同心描边。
         *
         * **注意这些 alpha 是叠加的**，不是取最大：核心位置六层全压在一起，
         * 1 - Π(1-a) 才是真正看到的不透明度。上一版 0.03+0.045+0.07+0.11+0.19+0.32
         * 叠加出来是 0.63，直接变成一条 RGB 灯带。
         *
         * 这一组叠出来约 0.26：够看清是一道光，又不至于在深色界面上抢戏。
         * 改任何一层都要重新算这条曲线，只调最内层会骗人。
         */
        val EDGE_LAYERS: List<EdgeLayer> = listOf(
            EdgeLayer(28f, 0.012f),
            EdgeLayer(18f, 0.018f),
            EdgeLayer(11f, 0.028f),
            EdgeLayer(6.5f, 0.045f),
            EdgeLayer(3.6f, 0.075f),
            EdgeLayer(2.0f, 0.120f),
        )

        const val PERIOD_MS = 3400L

        /** 光带占整圈的比例：从 [BAND_START] 开始，到 [BAND_END] 收尾。 */
        const val BAND_START = 0.62f
        const val BAND_END = 0.96f

        /**
         * 七色，都往浅里调了一档（混入一点白）：纯饱和的 #FF0000 在 0.24 透明度下依然刺眼，
         * 而 #FF8A8A 这种"洗过"的红才是光带该有的颜色。
         */
        val RAINBOW: IntArray = intArrayOf(
            Color.parseColor("#FF8A8A"),
            Color.parseColor("#FFC46B"),
            Color.parseColor("#FFE98A"),
            Color.parseColor("#9FE89F"),
            Color.parseColor("#8FE3FF"),
            Color.parseColor("#9FB4FF"),
            Color.parseColor("#D0A0FF"),
        )

        val PILL_BLACK: Int = Color.parseColor("#FF000000")
        val DOT_COLOR: Int = Color.parseColor("#8FE3FF")
    }
}
