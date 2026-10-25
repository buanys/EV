package ev.buan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * Line glyphs drawn in code.
 *
 * The host ships icons-core only (see EvIcons), and none of the shapes this design needs - menu,
 * plus, mic, atom, send, screen, chat, globe, memory chip, refresh, trash - exist there. Drawing them
 * keeps the icon pack off the shared surface and lets plugins restyle them by passing a color.
 */
enum class EvGlyphKind {
    Menu, Plus, Mic, Atom, Send, Screen, Chat, Globe, Memory, More,
    Bell, Clock, Person, Grid, Gear, Search, Doc, Lock, Sparkle, Refresh, Trash,
}

@Composable
fun EvGlyph(
    kind: EvGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.6f,
) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = strokeWidth * density
        val c = Offset(size.width / 2f, size.height / 2f)
        when (kind) {
            EvGlyphKind.Menu -> {
                repeat(2) { i ->
                    val y = c.y - s * 0.18f + i * s * 0.36f
                    drawLine(color, Offset(c.x - s * 0.32f, y), Offset(c.x + s * 0.32f, y), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            EvGlyphKind.Plus -> {
                drawLine(color, Offset(c.x - s * 0.28f, c.y), Offset(c.x + s * 0.28f, c.y), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, Offset(c.x, c.y - s * 0.28f), Offset(c.x, c.y + s * 0.28f), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            EvGlyphKind.Mic -> {
                drawRoundRect(color, Offset(c.x - s * 0.12f, c.y - s * 0.34f), Size(s * 0.24f, s * 0.4f), androidx.compose.ui.geometry.CornerRadius(s * 0.12f), style = Stroke(w))
                drawArc(color, 0f, 180f, false, Offset(c.x - s * 0.24f, c.y - s * 0.02f), Size(s * 0.48f, s * 0.34f), style = Stroke(w))
                drawLine(color, Offset(c.x, c.y + s * 0.3f), Offset(c.x, c.y + s * 0.4f), w)
            }
            EvGlyphKind.Atom -> {
                drawCircle(color, s * 0.34f, c, style = Stroke(w))
                drawLine(color, Offset(c.x - s * 0.34f, c.y), Offset(c.x + s * 0.34f, c.y), w)
                drawCircle(color, s * 0.07f, c)
            }
            EvGlyphKind.Send -> {
                drawLine(color, Offset(c.x, c.y + s * 0.3f), Offset(c.x, c.y - s * 0.3f), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, Offset(c.x - s * 0.16f, c.y - s * 0.14f), Offset(c.x, c.y - s * 0.3f), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, Offset(c.x + s * 0.16f, c.y - s * 0.14f), Offset(c.x, c.y - s * 0.3f), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            EvGlyphKind.Screen -> {
                drawRoundRect(color, Offset(c.x - s * 0.3f, c.y - s * 0.26f), Size(s * 0.6f, s * 0.44f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = Stroke(w))
                drawLine(color, Offset(c.x - s * 0.1f, c.y + s * 0.3f), Offset(c.x + s * 0.1f, c.y + s * 0.3f), w)
            }
            EvGlyphKind.Chat -> {
                drawRoundRect(color, Offset(c.x - s * 0.32f, c.y - s * 0.28f), Size(s * 0.64f, s * 0.46f), androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = Stroke(w))
                drawLine(color, Offset(c.x - s * 0.1f, c.y + s * 0.18f), Offset(c.x - s * 0.16f, c.y + s * 0.3f), w)
            }
            EvGlyphKind.Globe -> {
                drawCircle(color, s * 0.3f, c, style = Stroke(w))
                drawLine(color, Offset(c.x - s * 0.3f, c.y), Offset(c.x + s * 0.3f, c.y), w)
                drawOval(color, Offset(c.x - s * 0.14f, c.y - s * 0.3f), Size(s * 0.28f, s * 0.6f), style = Stroke(w))
            }
            EvGlyphKind.Memory -> {
                drawRoundRect(color, Offset(c.x - s * 0.26f, c.y - s * 0.22f), Size(s * 0.52f, s * 0.44f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = Stroke(w))
                repeat(3) { i ->
                    val x = c.x - s * 0.16f + i * s * 0.16f
                    drawLine(color, Offset(x, c.y - s * 0.3f), Offset(x, c.y - s * 0.22f), w)
                    drawLine(color, Offset(x, c.y + s * 0.22f), Offset(x, c.y + s * 0.3f), w)
                }
            }
            EvGlyphKind.Bell -> {
                drawArc(color, 200f, 140f, false, Offset(c.x - s * 0.26f, c.y - s * 0.26f), Size(s * 0.52f, s * 0.52f), style = Stroke(w))
                drawLine(color, Offset(c.x - s * 0.26f, c.y + s * 0.06f), Offset(c.x - s * 0.3f, c.y + s * 0.22f), w)
                drawLine(color, Offset(c.x + s * 0.26f, c.y + s * 0.06f), Offset(c.x + s * 0.3f, c.y + s * 0.22f), w)
                drawLine(color, Offset(c.x - s * 0.3f, c.y + s * 0.22f), Offset(c.x + s * 0.3f, c.y + s * 0.22f), w)
            }
            EvGlyphKind.Clock -> {
                drawCircle(color, s * 0.3f, c, style = Stroke(w))
                drawLine(color, c, Offset(c.x, c.y - s * 0.18f), w)
                drawLine(color, c, Offset(c.x + s * 0.14f, c.y), w)
            }
            EvGlyphKind.Person -> {
                drawCircle(color, s * 0.13f, Offset(c.x, c.y - s * 0.14f), style = Stroke(w))
                drawArc(color, 200f, 140f, false, Offset(c.x - s * 0.26f, c.y + s * 0.02f), Size(s * 0.52f, s * 0.4f), style = Stroke(w))
            }
            EvGlyphKind.Grid -> {
                repeat(2) { row ->
                    repeat(2) { col ->
                        drawRoundRect(color, Offset(c.x - s * 0.28f + col * s * 0.3f, c.y - s * 0.28f + row * s * 0.3f), Size(s * 0.24f, s * 0.24f), androidx.compose.ui.geometry.CornerRadius(s * 0.05f), style = Stroke(w))
                    }
                }
            }
            EvGlyphKind.Gear -> {
                drawCircle(color, s * 0.16f, c, style = Stroke(w))
                repeat(6) { i ->
                    val a = Math.toRadians(i * 60.0)
                    val x1 = c.x + (s * 0.24f) * kotlin.math.cos(a).toFloat()
                    val y1 = c.y + (s * 0.24f) * kotlin.math.sin(a).toFloat()
                    val x2 = c.x + (s * 0.33f) * kotlin.math.cos(a).toFloat()
                    val y2 = c.y + (s * 0.33f) * kotlin.math.sin(a).toFloat()
                    drawLine(color, Offset(x1, y1), Offset(x2, y2), w)
                }
            }
            EvGlyphKind.Search -> {
                drawCircle(color, s * 0.22f, Offset(c.x - s * 0.06f, c.y - s * 0.06f), style = Stroke(w))
                drawLine(color, Offset(c.x + s * 0.12f, c.y + s * 0.12f), Offset(c.x + s * 0.3f, c.y + s * 0.3f), w)
            }
            EvGlyphKind.Doc -> {
                drawRoundRect(color, Offset(c.x - s * 0.2f, c.y - s * 0.3f), Size(s * 0.4f, s * 0.6f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = Stroke(w))
                repeat(3) { i ->
                    val y = c.y - s * 0.12f + i * s * 0.12f
                    drawLine(color, Offset(c.x - s * 0.1f, y), Offset(c.x + s * 0.1f, y), w)
                }
            }
            EvGlyphKind.Lock -> {
                drawRoundRect(color, Offset(c.x - s * 0.2f, c.y - s * 0.06f), Size(s * 0.4f, s * 0.34f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = Stroke(w))
                drawArc(color, 180f, 180f, false, Offset(c.x - s * 0.15f, c.y - s * 0.26f), Size(s * 0.3f, s * 0.3f), style = Stroke(w))
            }
            EvGlyphKind.Sparkle -> {
                drawLine(color, Offset(c.x, c.y - s * 0.3f), Offset(c.x, c.y + s * 0.3f), w)
                drawLine(color, Offset(c.x - s * 0.3f, c.y), Offset(c.x + s * 0.3f, c.y), w)
                drawLine(color, Offset(c.x - s * 0.2f, c.y - s * 0.2f), Offset(c.x + s * 0.2f, c.y + s * 0.2f), w * 0.6f)
                drawLine(color, Offset(c.x + s * 0.2f, c.y - s * 0.2f), Offset(c.x - s * 0.2f, c.y + s * 0.2f), w * 0.6f)
            }
            EvGlyphKind.More -> {
                repeat(3) { i ->
                    drawCircle(color, s * 0.045f, Offset(c.x - s * 0.2f + i * s * 0.2f, c.y))
                }
            }
            // Clockwise rotation: three quarters of a circle with the head entering the open
            // quadrant from the top right, i.e. "run that again".
            EvGlyphKind.Refresh -> {
                val radius = s * 0.28f
                drawArc(
                    color,
                    startAngle = 45f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(c.x - radius, c.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                // The arc ends at 315 degrees; the head is two barbs behind that tip, along the
                // tangent, so the stroke visually turns into an arrow instead of stopping.
                val tip = Offset(c.x + radius * 0.7071f, c.y - radius * 0.7071f)
                drawLine(
                    color,
                    tip,
                    Offset(tip.x - s * 0.149f, tip.y - s * 0.035f),
                    w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    color,
                    tip,
                    Offset(tip.x - s * 0.035f, tip.y - s * 0.149f),
                    w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            // Waste paper bin: lid plus handle, a slightly tapered body and two ribs.
            EvGlyphKind.Trash -> {
                drawRoundRect(
                    color,
                    Offset(c.x - s * 0.11f, c.y - s * 0.32f),
                    Size(s * 0.22f, s * 0.1f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.04f),
                    style = Stroke(w),
                )
                drawLine(
                    color,
                    Offset(c.x - s * 0.32f, c.y - s * 0.22f),
                    Offset(c.x + s * 0.32f, c.y - s * 0.22f),
                    w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(color, Offset(c.x - s * 0.24f, c.y - s * 0.18f), Offset(c.x - s * 0.19f, c.y + s * 0.3f), w)
                drawLine(color, Offset(c.x + s * 0.24f, c.y - s * 0.18f), Offset(c.x + s * 0.19f, c.y + s * 0.3f), w)
                drawLine(
                    color,
                    Offset(c.x - s * 0.19f, c.y + s * 0.3f),
                    Offset(c.x + s * 0.19f, c.y + s * 0.3f),
                    w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                repeat(2) { i ->
                    val x = c.x - s * 0.085f + i * s * 0.17f
                    drawLine(color, Offset(x, c.y - s * 0.08f), Offset(x, c.y + s * 0.17f), w * 0.75f)
                }
            }
        }
    }
}
