package ev.buan.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * EV-UI design tokens, the single source of truth for color / type / spacing / radius / motion.
 *
 * Design spec: docs/design/EV-UI设计规范.md. Components must read these tokens instead of
 * hardcoding values; anything not expressible as a token is a spec gap, not an invitation
 * to improvise.
 */

// ---------------------------------------------------------------------------
// Color
// ---------------------------------------------------------------------------

/** Brand accent used when Monet is disabled. Spec §1.3. */
val EvAccentDefault = Color(0xFF4C8DFF)

/**
 * Color tokens. The dark theme is a four-step surface ladder (surface0 page background through
 * surface3 user bubble) — grouping and hierarchy come from luminance steps, never from dividers.
 */
data class EvColorTokens(
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Hairline used inside grouped cards: a faint fog wash, indented, never full-width. */
    val dividerGhost: Color,
)

fun evDarkColorTokens(accent: Color = EvAccentDefault, pureBlack: Boolean = false): EvColorTokens =
    EvColorTokens(
        surface0 = if (pureBlack) Color.Black else Color(0xFF0E0E10),
        surface1 = Color(0xFF1A1B1E),
        surface2 = Color(0xFF232428),
        surface3 = Color(0xFF2E2F34),
        textPrimary = Color(0xFFF2F2F4),
        textSecondary = Color(0xFFB0B1B8),
        textTertiary = Color(0xFF7A7B83),
        accent = accent,
        accentSoft = accent.copy(alpha = 0.16f),
        onAccent = Color.White,
        success = Color(0xFF3ECF8E),
        warning = Color(0xFFF2B84B),
        danger = Color(0xFFF26D6D),
        dividerGhost = Color.White.copy(alpha = 0.04f),
    )

fun evLightColorTokens(accent: Color = EvAccentDefault): EvColorTokens =
    EvColorTokens(
        surface0 = Color(0xFFFAFAFB),
        surface1 = Color(0xFFF2F2F5),
        surface2 = Color(0xFFEBEBEF),
        surface3 = Color(0xFFE2E2E7),
        textPrimary = Color(0xFF1A1B1E),
        textSecondary = Color(0xFF5A5B63),
        textTertiary = Color(0xFF9A9BA3),
        accent = accent,
        accentSoft = accent.copy(alpha = 0.12f),
        onAccent = Color.White,
        success = Color(0xFF0F9D58),
        warning = Color(0xFFB26A00),
        danger = Color(0xFFD93025),
        dividerGhost = Color.Black.copy(alpha = 0.06f),
    )

// ---------------------------------------------------------------------------
// Typography
// ---------------------------------------------------------------------------

/** One step of the type scale. Kept as data so unit tests can inspect it without a composition. */
data class EvTypeToken(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
) {
    fun style(color: Color = Color.Unspecified): TextStyle = TextStyle(
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
    )
}

/** Type scale. Chinese body text keeps line height >= 1.6x font size (spec §2.1). */
data class EvTypeTokens(
    val display: EvTypeToken,
    val title: EvTypeToken,
    val body: EvTypeToken,
    val label: EvTypeToken,
    val caption: EvTypeToken,
    val micro: EvTypeToken,
)

val DefaultEvTypeTokens = EvTypeTokens(
    display = EvTypeToken(28.sp, 36.sp, FontWeight.Medium),
    title = EvTypeToken(18.sp, 26.sp, FontWeight.Medium),
    body = EvTypeToken(16.sp, 26.sp, FontWeight.Normal),
    label = EvTypeToken(14.sp, 20.sp, FontWeight.Medium),
    caption = EvTypeToken(13.sp, 18.sp, FontWeight.Normal),
    micro = EvTypeToken(11.sp, 14.sp, FontWeight.Medium),
)

// ---------------------------------------------------------------------------
// Spacing & radius
// ---------------------------------------------------------------------------

/** The only legal spacing values, in dp: 4 / 8 / 12 / 16 / 20 / 24 / 32 (spec §3.1). */
data class EvSpacingTokens(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
)

val DefaultEvSpacingTokens = EvSpacingTokens()

/** Radius ladder (spec §3.2): 8 / 14 / 20 plus the full capsule. */
data class EvRadiusTokens(
    val small: Dp = 8.dp,
    val medium: Dp = 14.dp,
    val large: Dp = 20.dp,
) {
    val smallShape: Shape get() = RoundedCornerShape(small)
    val mediumShape: Shape get() = RoundedCornerShape(medium)
    val largeShape: Shape get() = RoundedCornerShape(large)
    val fullShape: Shape get() = RoundedCornerShape(percent = 50)

    /**
     * User bubble: asymmetric, the corner pointing at the send side stays tight (6dp) so a run
     * of consecutive messages reads as one cluster (spec §3.2).
     */
    fun userBubbleShape(lastInCluster: Boolean = true): Shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = if (lastInCluster) 6.dp else 18.dp,
    )
}

val DefaultEvRadiusTokens = EvRadiusTokens()

// ---------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------

/**
 * The global motion language (spec §6). Every position/size change uses one of these springs;
 * components never invent their own physics. Reduced-motion degrades everything to a snap.
 */
object EvMotionTokens {
    const val FADE_FAST_MILLIS = 150

    /** Large-surface movement: drawers, panels. */
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 300f)

    /** Component-level movement: buttons, capsules, bubble entrance. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 600f)

    /** Emphasis moments with a slight overshoot: send, success feedback. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 400f)

    /** Pure alpha transitions — the only place a linear tween is allowed. */
    fun fadeFast(): TweenSpec<Float> = tween(FADE_FAST_MILLIS, easing = LinearEasing)

    /**
     * Honors the reduced-motion preference: any animated spec degrades to an instant switch.
     *
     * Typed as [FiniteAnimationSpec] on purpose: transitions like `expandVertically`/`scaleIn`
     * refuse the wider [AnimationSpec], and both `spring` and `snap` are finite anyway.
     */
    fun <T> reducedAware(reduced: Boolean, spec: () -> FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduced) snap() else spec()
}

// ---------------------------------------------------------------------------
// Composition wiring
// ---------------------------------------------------------------------------

internal val LocalEvColorTokens = staticCompositionLocalOf { evDarkColorTokens() }
internal val LocalEvTypeTokens = staticCompositionLocalOf { DefaultEvTypeTokens }
internal val LocalEvSpacingTokens = staticCompositionLocalOf { DefaultEvSpacingTokens }
internal val LocalEvRadiusTokens = staticCompositionLocalOf { DefaultEvRadiusTokens }

/**
 * Token accessor, following the MaterialTheme/MiuixTheme pattern: the composable [EvTheme]
 * provides the values, components read them here.
 */
object EvThemeTokens {
    val colors: EvColorTokens
        @Composable @ReadOnlyComposable get() = LocalEvColorTokens.current

    val type: EvTypeTokens
        @Composable @ReadOnlyComposable get() = LocalEvTypeTokens.current

    val spacing: EvSpacingTokens
        @Composable @ReadOnlyComposable get() = LocalEvSpacingTokens.current

    val radii: EvRadiusTokens
        @Composable @ReadOnlyComposable get() = LocalEvRadiusTokens.current

    /** Whether animated specs must degrade to instant switches (spec §6.4 / §9). */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalEvAppearance.current.reducedMotionEnabled
}
