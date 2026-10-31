package ev.buan.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid-glass parameters. Tier 1 is data only: the glass renderer that consumes these values
 * is a later step, so today they document the knobs a theme may turn.
 */
data class EvGlassSpec(
    /** Backdrop blur radius behind a glass surface. */
    val blurRadius: Dp = 24.dp,
    /** Opacity of the tint laid over the blurred backdrop. */
    val tintAlpha: Float = 0.6f,
    /** Strength of the top-edge highlight that sells the "liquid" look. */
    val highlightAlpha: Float = 0.18f,
    /** Opacity of the hairline border around a glass surface. */
    val borderAlpha: Float = 0.12f,
)

/**
 * A complete visual definition, supplied to [EvTheme]. Replacing this object replaces the look of
 * the whole app: the host, ev-ui and every plugin read tokens through the composition locals it
 * feeds, so no component needs to know which theme is active.
 *
 * Colors are builders rather than fixed values because the accent and pure-black switch are user
 * appearance settings that the theme must respect.
 *
 * Motion is intentionally not part of the spec yet: spring specs are read from non-composable
 * coroutine code (drawer settling), so they cannot come from a composition local. That moves in a
 * later tier.
 */
data class EvThemeSpec(
    /** Stable identifier persisted in the appearance state. Must be unique across the registry. */
    val id: String = "ev-default",
    /** Name shown in the theme picker. */
    val name: String = "默认",
    /** One line shown under the name in the theme picker. */
    val description: String = "内置深色设计",
    val darkColors: (accent: Color, pureBlack: Boolean) -> EvColorTokens = { accent, pureBlack ->
        evDarkColorTokens(accent = accent, pureBlack = pureBlack)
    },
    val lightColors: (accent: Color) -> EvColorTokens = { accent ->
        evLightColorTokens(accent = accent)
    },
    val type: EvTypeTokens = DefaultEvTypeTokens,
    val spacing: EvSpacingTokens = DefaultEvSpacingTokens,
    val radii: EvRadiusTokens = DefaultEvRadiusTokens,
    val glass: EvGlassSpec = EvGlassSpec(),
    /** Spring physics and fade timing. A theme may move slower, tighter or livelier. */
    val motion: EvMotionSpec = EvMotionSpec.Default,
) {
    companion object {
        /** The built-in theme. Its values are exactly the ones the tokens used before this split. */
        val Default: EvThemeSpec = EvThemeSpec()
    }
}

internal val LocalEvGlassSpec = staticCompositionLocalOf { EvGlassSpec() }
