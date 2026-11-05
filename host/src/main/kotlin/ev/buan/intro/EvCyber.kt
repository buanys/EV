package ev.buan.intro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * EV cyber token layer.
 *
 * Values follow the Relic Interface System "cyber" skin (MIT) so the numbers are defensible
 * rather than invented: graphite-not-black surfaces, radius 0, 45-degree chamfers at 6/10/16dp,
 * 1px hard borders and no soft shadows, accents that carry state, and a 80/140/200/240ms motion
 * scale that never eases in. The code is EV's own; nothing is vendored.
 *
 * The one rule that matters most for a boot screen: an accent that carries no meaning must not be
 * used, and no readout may be invented. Everything shown on these pages comes from the device or
 * from the host's real plugin state.
 */
object EvCyber {

    /* ── surfaces (graphite, never pure black) ─────────────────────────────── */
    val Void = Color(0xFF070406)
    val Bg = Color(0xFF0A0608)
    val Surface1 = Color(0xFF120A0D)
    val Surface2 = Color(0xFF1A0E12)
    val Surface3 = Color(0xFF241318)
    val Surface4 = Color(0xFF2C171D)

    /* ── structural lines ──────────────────────────────────────────────────── */
    val Line = Color(0xFF6E2D38)
    val LineStrong = Color(0xFF9A3A48)
    val LineFaint = Color(0xFF3A1820)

    /* ── text ──────────────────────────────────────────────────────────────── */
    val Fg1 = Color(0xFFF3EEF0)
    val Fg2 = Color(0xFFC8B9BD)
    val Fg3 = Color(0xFFB09AA0)
    val Fg4 = Color(0xFF6A5258)
    val OnAccent = Color(0xFF0A0608)

    /* ── accents: every one of these means something ───────────────────────── */
    val Red = Color(0xFFFF4D62)      // danger / blocked
    val RedFill = Color(0xFFFF003C)
    val Cyan = Color(0xFF3DF0FF)     // data / scan
    val CyanFill = Color(0xFF00E5FF)
    val Green = Color(0xFF34F08C)    // online / approved
    val GreenFill = Color(0xFF00E57E)
    val Yellow = Color(0xFFFFD83A)   // active / CTA / pending
    val YellowFill = Color(0xFFFFE23A)
    val Violet = Color(0xFFB08AFF)   // inference
    val VioletFill = Color(0xFF9A5CFF)
    val Magenta = Color(0xFFFF5FD2)  // runtime
    val MagentaFill = Color(0xFFFF42C8)
    val Orange = Color(0xFFFF9F4A)   // correction

    val RedDim = Color(0xFF8F1F30)
    val CyanDim = Color(0xFF1F8A99)
    val GreenDim = Color(0xFF1F8A52)
    val YellowDim = Color(0xFF8F7A1F)

    val Scanline = Color(0x0DFF2846)
    val GlowRed = Color(0x29FF4D62)
    val GlowCyan = Color(0x293DF0FF)
    val GlowGreen = Color(0x2934F08C)
    val GlowYellow = Color(0x29FFD83A)
}

/* ── shape: radius 0, two opposite corners cut at 45° ─────────────────────── */

val EvCutSm: Dp = 6.dp   // chips, buttons, inputs
val EvCut: Dp = 10.dp    // panels, cards
val EvCutLg: Dp = 16.dp  // modals, hero blocks

/** Cuts the top-right and bottom-left corners; the mirrored variant cuts the other two. */
fun evChamfer(cut: Dp = EvCut, mirror: Boolean = false): Shape = EvChamferShape(cut, mirror)

private class EvChamferShape(private val cut: Dp, private val mirror: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        val p = Path().apply {
            if (!mirror) {
                moveTo(0f, 0f); lineTo(w - c, 0f); lineTo(w, c)
                lineTo(w, h); lineTo(c, h); lineTo(0f, h - c); close()
            } else {
                moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c)
                lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
            }
        }
        return Outline.Generic(p)
    }
}

/* ── spacing: 4dp base grid ───────────────────────────────────────────────── */

object EvSpace {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s7 = 32.dp; val s8 = 48.dp
}

/* ── motion: fast, and never ease-in on the way in ────────────────────────── */

object EvMotion {
    const val INSTANT = 80
    const val FAST = 140
    const val BASE = 200
    const val ENTER = 240

    /** cubic-bezier(0.22, 1, 0.36, 1) */
    val EaseOut: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** cubic-bezier(0.16, 1, 0.3, 1) */
    val Snap: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

/* ── drawing primitives ───────────────────────────────────────────────────── */

/** Path for a chamfered panel at [topLeft], cutting the same two corners as [evChamfer]. */
internal fun chamferPath(w: Float, h: Float, cut: Float, mirror: Boolean = false): Path {
    val c = cut.coerceAtMost(minOf(w, h) / 2f)
    return Path().apply {
        if (!mirror) {
            moveTo(0f, 0f); lineTo(w - c, 0f); lineTo(w, c)
            lineTo(w, h); lineTo(c, h); lineTo(0f, h - c); close()
        } else {
            moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c)
            lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
        }
    }
}

/**
 * A RIS panel: flat surface fill, 1px hard border, chamfered corners. No glow, no soft shadow -
 * depth comes from the surface stack and the border, not from luminescence.
 */
internal fun DrawScope.evPanel(
    topLeft: Offset,
    size: Size,
    cut: Float,
    fill: Color = EvCyber.Surface1,
    border: Color = EvCyber.Line,
    borderWidth: Float = 1f,
    mirror: Boolean = false,
    alpha: Float = 1f,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val path = chamferPath(size.width, size.height, cut, mirror)
        .apply { translate(topLeft) }
    drawPath(path, fill.copy(alpha = fill.alpha * alpha))
    drawPath(path, border.copy(alpha = border.alpha * alpha), style = Stroke(borderWidth))
}

/** Discrete blocks: the RIS segmented meter. [lit] blocks are filled with [accent]. */
internal fun DrawScope.evSegMeter(
    topLeft: Offset,
    width: Float,
    height: Float,
    blocks: Int,
    lit: Float,
    accent: Color,
    dim: Color = EvCyber.Surface4,
    alpha: Float = 1f,
) {
    if (blocks <= 0 || width <= 0f) return
    val gap = 3f
    val bw = (width - gap * (blocks - 1)) / blocks
    val n = lit * blocks
    for (i in 0 until blocks) {
        val on = i < n
        drawRect(
            color = (if (on) accent else dim).copy(alpha = alpha),
            topLeft = Offset(topLeft.x + i * (bw + gap), topLeft.y),
            size = Size(bw, height),
        )
    }
}

/** 32dp grid, as the guideline prescribes for backgrounds. */
internal fun DrawScope.evGrid(stepPx: Float, color: Color, alpha: Float) {
    var x = 0f
    while (x < size.width) {
        drawLine(color.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), 1f)
        x += stepPx
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), 1f)
        y += stepPx
    }
}

/** Corner brackets: four L-shaped marks, the tactical frame. */
internal fun DrawScope.evBrackets(inset: Float, len: Float, color: Color, alpha: Float, width: Float = 1.5f) {
    val o = inset
    val r = size.width - inset
    val b = size.height - inset
    listOf(
        Triple(Offset(o, o), 1f, 1f),
        Triple(Offset(r, o), -1f, 1f),
        Triple(Offset(o, b), 1f, -1f),
        Triple(Offset(r, b), -1f, -1f),
    ).forEach { (p, dx, dy) ->
        drawLine(color.copy(alpha = alpha), p, Offset(p.x + dx * len, p.y), width)
        drawLine(color.copy(alpha = alpha), p, Offset(p.x, p.y + dy * len), width)
    }
}

/** Radial blip: the sanctioned glow, reserved for live acquisition. */
internal fun DrawScope.evGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
