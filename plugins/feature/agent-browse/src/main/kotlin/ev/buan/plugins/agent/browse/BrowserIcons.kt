package ev.buan.plugins.agent.browse

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 浏览器自己的图标。
 *
 * 设计系统里没有箭头和房子（只有 `Send` / `Chevron` / `Grid`），而给 `EvGlyphKind` 加值
 * 会让已编译插件的 `when` 崩溃（AGENTS.md 的硬规矩）。这几个图形只有这个面板用，
 * 画在这里就够了。
 */
internal enum class BrowserArrowKind {
    /** 一条带尾巴的向左长箭：离开面板，回到聊天。 */
    Back,

    /** 纯折线，向左：网页历史后退。 */
    ChevronLeft,

    /** 纯折线，向右：网页历史前进。 */
    ChevronRight,
}

@Composable
internal fun BrowserArrow(
    kind: BrowserArrowKind,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = w * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply {
            when (kind) {
                BrowserArrowKind.Back -> {
                    // 尾巴
                    moveTo(w * 0.86f, h * 0.5f)
                    lineTo(w * 0.22f, h * 0.5f)
                    // 箭头
                    moveTo(w * 0.42f, h * 0.28f)
                    lineTo(w * 0.20f, h * 0.5f)
                    lineTo(w * 0.42f, h * 0.72f)
                }

                BrowserArrowKind.ChevronLeft -> {
                    moveTo(w * 0.62f, h * 0.24f)
                    lineTo(w * 0.36f, h * 0.5f)
                    lineTo(w * 0.62f, h * 0.76f)
                }

                BrowserArrowKind.ChevronRight -> {
                    moveTo(w * 0.38f, h * 0.24f)
                    lineTo(w * 0.64f, h * 0.5f)
                    lineTo(w * 0.38f, h * 0.76f)
                }
            }
        }
        drawPath(path, color, style = stroke)
    }
}

/** 房子：底部工具栏的"回首页"。 */
@Composable
internal fun BrowserHomeGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = w * 0.1f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply {
            // 屋顶
            moveTo(w * 0.16f, h * 0.48f)
            lineTo(w * 0.5f, h * 0.18f)
            lineTo(w * 0.84f, h * 0.48f)
            // 墙
            moveTo(w * 0.27f, h * 0.43f)
            lineTo(w * 0.27f, h * 0.82f)
            lineTo(w * 0.73f, h * 0.82f)
            lineTo(w * 0.73f, h * 0.43f)
        }
        drawPath(path, color, style = stroke)
    }
}
