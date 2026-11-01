package ev.buan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Depth, as three alphas a theme can tune.
 *
 * The app has no shadows - a dark theme's shadow is just a darker rectangle - so hierarchy is carried
 * by two things instead: a hairline where a surface ends, and a faint top highlight on anything that
 * floats over the page. Before this existed those numbers were improvised per component, or missing,
 * which is why grouped cards read as flat as the page behind them.
 */
data class EvElevationSpec(
    /** Border on a grouped card sitting directly on the page. */
    val cardBorderAlpha: Float = 0.05f,
    /** Border on a floating surface: drawer, quick menu, model card. */
    val floatBorderAlpha: Float = 0.10f,
    /** Top-edge highlight that makes a floating surface read as above the page. */
    val floatHighlightAlpha: Float = 0.06f,
) {
    companion object {
        val Default: EvElevationSpec = EvElevationSpec()
    }
}

/**
 * A hairline in the theme's own ink. Call it inside `Modifier` chains of a surface that has already
 * declared its shape, so the border follows that shape instead of inventing a second corner radius.
 */
@Composable
fun Modifier.evHairline(alpha: Float, shape: Shape): Modifier {
    if (alpha <= 0f) return this
    val ink = EvThemeTokens.colors.textPrimary.copy(alpha = alpha)
    return this
        .clip(shape)
        .border(0.5.dp, ink, shape)
}

/**
 * A floating surface's signature: hairline plus a top highlight.
 *
 * The highlight is a vertical gradient that fades within the first few dp, which is what a lit edge
 * looks like at this scale - a full-surface tint would read as just a lighter fill.
 */
@Composable
fun Modifier.evFloatingSurface(shape: Shape): Modifier {
    val elevation = EvThemeTokens.elevation
    val highlight = EvThemeTokens.colors.textPrimary.copy(alpha = elevation.floatHighlightAlpha)
    return this
        .evHairline(elevation.floatBorderAlpha, shape)
        .drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(highlight, highlight.copy(alpha = 0f)),
                    startY = 0f,
                    endY = 6.dp.toPx(),
                ),
                size = size.copy(height = 6.dp.toPx()),
            )
        }
}

/**
 * Press feedback: the surface dips slightly instead of only changing colour.
 *
 * Scale carries the press on a dark theme, where a subtle tint change is hard to see on large cards.
 * The spring is the same `snappy` one every button uses, so a tap and a page change feel like the
 * same interface.
 */
@Composable
fun Modifier.evPressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.975f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = EvMotionTokens.snappy(),
        label = "evPress",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
