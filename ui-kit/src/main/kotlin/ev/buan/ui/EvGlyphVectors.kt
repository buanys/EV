package ev.buan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * EV 的图标语言本身：网格、描边、以及四个绘制原语。具体有哪些图标见
 * `EvGlyphControls.kt`（动词）与 `EvGlyphSubjects.kt`（名词）。
 *
 * 这个文件存在的理由：图标原来全是 miuix 的系统符号，按"最接近的比喻"借来——
 * 用爱心表示助手、用链条表示浏览器、用旋钮表示网页——界面读起来像一个系统设置页
 * 上硬贴了一个聊天窗。借来的比喻做不出高级感，只能自己画。
 *
 * 语言定在这里，保证以后每个图标一致：
 *  - 24 x 24 网格，有效区 3.5..20.5，视觉中心 12,12
 *  - 1.7dp 描边，圆头圆角，除刻意的圆点外一律不填充
 *  - 只用圆、圆角矩形和直线组成，不做插画
 *  - 一个图标一个含义；两个概念要同一个标记，说明其中一个错了
 */
private const val STROKE = 2.0f
/** 默认墨色。调用方负责着色，这里的黑只是占位。跨文件给图标用。 */
internal val ink = SolidColor(Color.Black)

/**
 * Draws one mark in EV's own icon language: 24dp grid, 2dp round-capped stroke, black ink (the
 * caller tints it). Exposed so a theme plugin can redraw a glyph without copying the builder setup
 * or inventing a second visual grammar.
 */
fun evThemeGlyph(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

/** Ink a theme glyph is drawn with before tinting. */
val EvThemeGlyphInk = SolidColor(Color.Black)

/** Stroke width of the icon language, in the 24 unit grid. */
const val EV_GLYPH_STROKE = 2.0f

internal fun glyph(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

internal fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(
    fill = null,
    stroke = ink,
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

internal fun ImageVector.Builder.dot(block: PathBuilder.() -> Unit) = path(
    fill = ink,
    pathBuilder = block,
)

/** A filled dot, drawn as two arcs: PathBuilder has no addOval in this Compose version. */
internal fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

/** The drawer. Three rules of equal weight: the least clever way to say "menu", so nobody misreads it. */
