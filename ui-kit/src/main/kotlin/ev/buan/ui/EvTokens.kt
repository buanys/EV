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
import com.itos.coui.theme.CouiPresets
import com.itos.coui.shape.CouiShape
import com.itos.coui.shape.CouiCapsuleShape

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

fun evDarkColorTokens(accent: Color = EvAccentDefault, pureBlack: Boolean = false): EvColorTokens {
    // 值全部来自 COUI 的预设色板，一个都不自己定：之前自造的 0xFF141417 这类值
    // 和 COUI 组件（菜单、Fab、开关）放在一起就是两种黑。
    val preset = CouiPresets.Dark
    // 纯黑模式下**必须重新推导四级**，不能只把最底下那级换成 #000：
    // COUI 的深色本身只有两级明度（container #1E1E1E / card #333333），
    // 底一旦变成纯黑，`container` 就变成"最亮的那一级"，四级阶梯塌成两级，
    // 屏幕上一排容器全是同一个灰——这正是"看起来毛躁、没有层次"的来源。
    // 下面这一组按 0 / 22 / 30 / 42 的明度排开，相邻差值都在 8 以上，
    // 人眼才分得出来；同时最上面那级仍然是 COUI 的 #1E1E1E。
    val ladder = if (pureBlack) {
        listOf(Color(0xFF000000), Color(0xFF161616), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    } else {
        listOf(preset.background, preset.container, preset.surface, preset.card)
    }
    return EvColorTokens(
        surface0 = ladder[0],
        surface1 = ladder[1],
        surface2 = ladder[2],
        surface3 = ladder[3],
        textPrimary = preset.onSurface,
        textSecondary = preset.onSurfaceVariant,
        textTertiary = preset.onSurfaceVariant.copy(alpha = 0.72f),
        accent = accent,
        accentSoft = preset.accent.copy(alpha = 0.18f),
        onAccent = preset.onAccent,
        success = Color(0xFF3ECF8E),
        warning = Color(0xFFF2B84B),
        danger = Color(0xFFF26D6D),
        dividerGhost = preset.outlineVariant,
    )
}

fun evLightColorTokens(accent: Color = EvAccentDefault): EvColorTokens {
    val preset = CouiPresets.Light
    return EvColorTokens(
        surface0 = preset.background,
        surface1 = preset.container,
        surface2 = preset.surface,
        surface3 = preset.card,
        textPrimary = preset.onSurface,
        textSecondary = preset.onSurfaceVariant,
        textTertiary = preset.onSurfaceVariant.copy(alpha = 0.72f),
        accent = accent,
        accentSoft = preset.accent.copy(alpha = 0.14f),
        onAccent = preset.onAccent,
        success = Color(0xFF0F9D58),
        warning = Color(0xFFB26A00),
        danger = Color(0xFFD93025),
        dividerGhost = preset.outlineVariant,
    )
}

// ---------------------------------------------------------------------------
// Typography
// ---------------------------------------------------------------------------

/** One step of the type scale. Kept as data so unit tests can inspect it without a composition. */
data class EvTypeToken(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
    /** 大字收紧、小字放开：中文标题在大字号下需要负字距才不松。 */
    val letterSpacing: TextUnit = 0.sp,
) {
    fun style(color: Color = Color.Unspecified): TextStyle = TextStyle(
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        color = color,
    )
}

/** Type scale. Chinese body text keeps line height >= 1.6x font size (spec §2.1). */
data class EvTypeTokens(
    val display: EvTypeToken,
    /**
     * Page title: the one line that names the screen.
     *
     * Added after the 2026-10-26 cross-screen audit found every second-level page topping out at
     * [title] — the same size as its own list rows — so entering a page had no visual entry point
     * at all. 22sp sits three steps over body and six under display: far enough to read as "here
     * you are", close enough that it never competes with the home hero.
     */
    val headline: EvTypeToken,
    val title: EvTypeToken,
    val body: EvTypeToken,
    val label: EvTypeToken,
    val caption: EvTypeToken,
    val micro: EvTypeToken,
)

val DefaultEvTypeTokens = EvTypeTokens(
    // 字阶拉开到"一眼、扫视、细节"三层：标题更重更紧，正文更松更好读，
    // 小字加字距——这是让页面看起来像排过版、而不是把字号排成一列的关键。
    display = EvTypeToken(28.sp, 36.sp, FontWeight.Bold, letterSpacing = (-0.3).sp),
    headline = EvTypeToken(24.sp, 32.sp, FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    title = EvTypeToken(19.sp, 27.sp, FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    body = EvTypeToken(15.5.sp, 25.sp, FontWeight.Normal),
    label = EvTypeToken(14.sp, 20.sp, FontWeight.Medium),
    caption = EvTypeToken(12.5.sp, 19.sp, FontWeight.Normal),
    micro = EvTypeToken(11.sp, 15.sp, FontWeight.Medium, letterSpacing = 0.4.sp),
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
    val small: Dp = 10.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 20.dp,
) {
    val smallShape: Shape get() = CouiShape(small)
    val mediumShape: Shape get() = CouiShape(medium)
    val largeShape: Shape get() = CouiShape(large)
    val fullShape: Shape get() = CouiCapsuleShape

    /**
     * User bubble: asymmetric, the corner pointing at the send side stays tight (6dp) so a run
     * of consecutive messages reads as one cluster (spec §3.2).
     */
    fun userBubbleShape(lastInCluster: Boolean = true): Shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = if (lastInCluster) 0.dp else 18.dp,
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
/**
 * How the app moves, as data, so a theme can retune it without touching a component.
 *
 * Springs are the app's only position/size animation, and they are read from places that are not
 * composables (drawer settle, gesture flings), which is why the active spec lives in
 * [EvMotionState] rather than in a composition local.
 */
data class EvMotionSpec(
    /** Large-surface movement: drawers, panels. */
    val gentleDamping: Float = 0.8f,
    val gentleStiffness: Float = 300f,
    /** Component-level movement: buttons, capsules, bubble entrance. */
    val snappyDamping: Float = 0.75f,
    val snappyStiffness: Float = 600f,
    /** Emphasis moments with a slight overshoot: send, success feedback. */
    val bouncyDamping: Float = 0.6f,
    val bouncyStiffness: Float = 400f,
    /** Pure-alpha transitions; the one place a linear tween is allowed. */
    val fadeMillis: Int = 150,
) {
    companion object {
        val Default: EvMotionSpec = EvMotionSpec()
    }
}

/**
 * The motion spec of the theme currently on screen.
 *
 * [EvTheme] writes it while composing, before any child does, so composables and coroutine-based
 * callers see the same values. It is deliberately app-wide rather than a composition local: the
 * drawer and gesture code animate from lambdas that outlive a composition and cannot read one.
 *
 * Single-process, single-window assumption: two activities composing different themes would race,
 * and the last composition would win. The host applies one appearance to every activity, so this
 * holds; it is stated here because it is a real constraint, not an oversight.
 */
object EvMotionState {
    @Volatile
    var current: EvMotionSpec = EvMotionSpec.Default
}

object EvMotionTokens {
    const val FADE_FAST_MILLIS = 150

    /** Large-surface movement: drawers, panels. */
    fun <T> gentle(): SpringSpec<T> = with(EvMotionState.current) {
        spring(dampingRatio = gentleDamping, stiffness = gentleStiffness)
    }

    /** Component-level movement: buttons, capsules, bubble entrance. */
    fun <T> snappy(): SpringSpec<T> = with(EvMotionState.current) {
        spring(dampingRatio = snappyDamping, stiffness = snappyStiffness)
    }

    /** Emphasis moments with a slight overshoot: send, success feedback. */
    fun <T> bouncy(): SpringSpec<T> = with(EvMotionState.current) {
        spring(dampingRatio = bouncyDamping, stiffness = bouncyStiffness)
    }

    /** Pure alpha transitions — the only place a linear tween is allowed. */
    fun fadeFast(): TweenSpec<Float> =
        tween(EvMotionState.current.fadeMillis, easing = LinearEasing)

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

/** The active theme's icon language; unmentioned glyphs fall back to the built-in marks. */
internal val LocalEvIconSet = staticCompositionLocalOf { EvIconSet.Default }

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

    /** Liquid-glass parameters from the active [EvThemeSpec]. */
    val glass: EvGlassSpec
        @Composable @ReadOnlyComposable get() = LocalEvGlassSpec.current

    /** The active theme's icon language; unmentioned glyphs fall back to the built-in marks. */
    val icons: EvIconSet
        @Composable @ReadOnlyComposable get() = LocalEvIconSet.current

    /** Depth: hairline strength and the floating-surface highlight. */
    val elevation: EvElevationSpec
        @Composable @ReadOnlyComposable get() = LocalEvElevationSpec.current

    /** Whether animated specs must degrade to instant switches (spec §6.4 / §9). */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalEvAppearance.current.reducedMotionEnabled
}

/**
 * 页面内容的唯一边距：24dp。
 *
 * 这一条值不允许在别处出现第二种写法。上一版首页里有 15dp、24dp、30dp 三种左边距，
 * 屏幕左边出现三条垂直线，看起来"没对齐"——原因不是差了 48px，是**有多条线**。
 * 现在顶部栏、页头、列表、输入框全部读这一个值。
 */
val EvPageInset: Dp = 24.dp
