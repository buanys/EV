package ev.buan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import com.skydoves.cloudy.cloudy
import com.skydoves.cloudy.sky
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
/**
 * A floating surface made of glass: the captured backdrop, blurred, tinted and clipped to [shape].
 *
 * Degrades in two ways, both deliberate: no backdrop (a page that is not under a scaffold) or the
 * blur setting turned off renders an opaque surface, so the surface never becomes unreadable and a
 * weak device can opt out of the per-frame cost.
 */
@Composable
fun Modifier.evGlassFloating(shape: Shape): Modifier {
    val sky = LocalEvSky.current
    val enabled = LocalBlurEnabled.current
    val glass = EvThemeTokens.glass
    val tokens = EvThemeTokens.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    if (sky == null || !enabled) {
        return this.background(tokens.surface2, shape).evHairline(glass.borderAlpha, shape)
    }
    val radius = with(density) { glass.blurRadius.toPx() }.toInt()
    return this
        .clip(shape)
        .cloudy(
            sky = sky,
            radius = radius,
            tint = tokens.surface2.copy(alpha = glass.tintAlpha),
            shape = shape,
        )
        .evFloatingSurface(shape)
}

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

/**
 * A captured backdrop, handed to a plugin without exposing the glass library's types.
 *
 * The owning screen marks the scrolling content with [evBackdropSource] and paints each surface that
 * floats above it with [evGlassOver]. Cloudy's Sky stays an implementation detail of ui-kit, so a
 * plugin compiles against this module and never against the blur dependency.
 */
@Stable
class EvBackdrop internal constructor(internal val sky: com.skydoves.cloudy.Sky)

/** Creates the capture slot for one screen. One per screen, shared by everything that floats. */
@Composable
fun rememberEvBackdrop(): EvBackdrop = EvBackdrop(com.skydoves.cloudy.rememberSky())

/** Marks the content that sits *behind* glass surfaces; it is the thing that gets blurred. */
@Composable
fun Modifier.evBackdropSource(backdrop: EvBackdrop): Modifier =
    if (LocalBlurEnabled.current) this.sky(backdrop.sky)
    else this

/**
 * A glass *background* layer: paint it behind normal content instead of wrapping the content.
 *
 * The blur needs a captured frame, and until the first one arrives it draws nothing. Wrapping
 * content in it therefore makes the whole surface vanish for that first frame - which reads as
 * "the bar only appears after I tap something". Painting it as a separate layer keeps the content
 * visible at all times and lets the blur arrive a frame later.
 */
@Composable
fun Modifier.evGlassLayer(shape: Shape): Modifier {
    val sky = LocalEvSky.current
    val enabled = LocalBlurEnabled.current
    val glass = EvThemeTokens.glass
    val tokens = EvThemeTokens.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Always paint the tint: it is what makes the surface readable before the first capture.
    val base = this.background(tokens.surface1.copy(alpha = glass.tintAlpha), shape)
    if (!enabled) return base.evHairline(glass.borderAlpha, shape)
    if (sky == null) return base.evFloatingSurface(shape)
    val radius = with(density) { glass.blurRadius.toPx() }.toInt()
    return base
        .cloudy(sky = sky, radius = radius, shape = shape)
        .evFloatingSurface(shape)
}

/** Paints [shape] as glass over an [EvBackdrop]; opaque when blur is off. */
@Composable
fun Modifier.evGlassOver(backdrop: EvBackdrop, shape: Shape): Modifier {
    val enabled = LocalBlurEnabled.current
    val glass = EvThemeTokens.glass
    val tokens = EvThemeTokens.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    if (!enabled) {
        return this.background(tokens.surface2, shape).evHairline(glass.borderAlpha, shape)
    }
    val radius = with(density) { glass.blurRadius.toPx() }.toInt()
    return this
        .clip(shape)
        .cloudy(
            sky = backdrop.sky,
            radius = radius,
            tint = tokens.surface2.copy(alpha = glass.tintAlpha),
            shape = shape,
        )
        .evFloatingSurface(shape)
}
