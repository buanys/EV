package ev.buan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.CouiTypography
import com.itos.coui.shape.CouiShape

/**
 * 应用的总主题。
 *
 * COUI 提供两样东西：**材质与动效**（来自组件）和**一套颜色/字号槽位**（来自主题）。
 * 这里做的是把 EV 自己的 token 层接到 COUI 的槽位上——组件读 EV token，
 * 而 EV token 由这个主题从 [EvThemeSpec] 算出来。三者的关系是：
 *
 * ```
 * EvThemeSpec（主题插件给的坐标）
 *      └─ EvColors / EvType（EV 的语义 token，组件只认这个）
 *             └─ CouiColorScheme / CouiTypography（COUI 组件的槽位）
 * ```
 *
 * ## 为什么不再有 Monet 动态取色
 *
 * 那是 Miuix 的能力。COUI 的取色模型是「预设 + 强调色」，硬接会造出两层颜色系统
 * 互相打架：一个说背景跟着壁纸走，一个说背景来自主题文件。
 * 强调色仍由 [EvAppearance] 决定，用户能换的仍然是那一处。
 *
 * ## 字号
 *
 * COUI 的 `CouiTypography` 只有三个槽位（body / label / menuItem）。
 * EV 的七级字阶不塞进去——它比 COUI 的槽位细，塞进去会把最后一级压扁。
 * COUI 组件用自己的字阶，EV 组件读 [EvType]，两边各守各的刻度，靠 [EvTheme] 保证
 * 它们来自同一份 spec。这就是"总主题"的意思：**同一个源头，两套刻度**。
 */
@Composable
fun EvTheme(
    appearance: EvAppearance = EvAppearance(),
    /** Explicit theme for previews and tests. Null means: resolve [EvAppearance.themeId]. */
    spec: EvThemeSpec? = null,
    content: @Composable () -> Unit,
) {
    val theme = remember(spec, appearance.themeId) {
        spec ?: EvThemeRegistry.resolve(appearance.themeId)
    }
    // Set before CouiTheme and the content compose, so every child - and every coroutine a child
    // starts later - reads this theme's spring physics rather than the previous theme's.
    EvMotionState.current = theme.motion

    val isDark = when (appearance.themeMode) {
        EvThemeMode.SYSTEM -> isSystemInDarkTheme()
        EvThemeMode.LIGHT -> false
        EvThemeMode.DARK -> true
    }

    val accent = if (appearance.accentColor == EvAccentColor.SYSTEM) {
        EvAccentDefault
    } else {
        appearance.accentColor.seedColor()
    }

    val colorTokens = remember(isDark, appearance.pureBlackEnabled, accent, theme) {
        if (isDark) theme.darkColors(accent, appearance.pureBlackEnabled) else theme.lightColors(accent)
    }
    val typeTokens = theme.type

    val couiScheme = remember(colorTokens) { colorTokens.toCouiScheme() }

    CouiTheme(
        darkTheme = isDark,
        colorScheme = couiScheme,
        // typography 故意不传：字阶归 COUI 自己管。
        // 之前这里把 EV 的三档字（body/label/title）塞进 COUI 的槽位，结果同一屏里
        // COUI 组件用自己的字体族和字号、EV 组件用另一套，`PreferenceSummary` 和 `body`
        // 摆在一起时基线差一点——那种"说不出哪里不对"的观感就来自这里。
        // 现在只有一套字：`CouiTextKind`。
    ) {
        val platformDensity: Density = LocalDensity.current
        val scaledDensity = remember(platformDensity, appearance.interfaceScale) {
            if (appearance.interfaceScale == DEFAULT_INTERFACE_SCALE) {
                platformDensity
            } else {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            }
        }
        CompositionLocalProvider(
            LocalEvAppearance provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides scaledDensity,
            LocalEvColorTokens provides colorTokens,
            LocalEvTypeTokens provides typeTokens,
            LocalEvSpacingTokens provides theme.spacing,
            LocalEvRadiusTokens provides theme.radii,
            LocalEvGlassSpec provides theme.glass,
            LocalEvIconSet provides theme.icons,
            LocalEvElevationSpec provides theme.elevation,
        ) {
            content()
        }
    }
}

/**
 * EV 的语义色 → COUI 的槽位。
 *
 * 一一对应，不发明新语义：COUI 只有这一组槽位，多造一层名字只会让
 * "这个颜色该用哪个"变成每次都要想的问题。
 */
private fun EvColorTokens.toCouiScheme(): CouiColorScheme = CouiColorScheme(
    accent = accent,
    onAccent = onAccent,
    surface = surface0,
    container = surface1,
    onSurface = textPrimary,
    onSurfaceVariant = textSecondary,
    outlineVariant = dividerGhost,
)

/**
 * EV 的字阶 → COUI 的三个槽位。
 *
 * 只映射能对上的三级：body→body、label→label、title→menuItem（COUI 把列表项文字
 * 叫 menuItem，语义上就是"一行里主要的那句话"）。其余四级留在 EV 这边，
 * 由 EV 组件用 [EvTypeTokens] 直接读——**不往 COUI 的槽位里硬塞**。
 */
private fun EvTypeTokens.toCouiTypography(): CouiTypography = CouiTypography(
    body = body.style(),
    label = caption.style(),
    menuItem = title.style(),
)

private fun EvTypeToken.style(color: Color = Color.Unspecified): TextStyle = TextStyle(
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    color = color,
)

/** 常用形状。集中在这里，避免每个组件各写一个 `CouiShape(18.dp)`。 */
object EvShapes {
    val card = CouiShape(20.dp)
    val composer = CouiShape(26.dp)
    val bubble = CouiShape(18.dp)
    val block = CouiShape(18.dp)
}

internal fun EvAccentColor.seedColor(): Color = when (this) {
    EvAccentColor.SYSTEM, EvAccentColor.BLUE -> Color(0xFF3482FF)
    EvAccentColor.PURPLE -> Color(0xFF6750A4)
    EvAccentColor.PINK -> Color(0xFFB0006D)
    EvAccentColor.RED -> Color(0xFFBA1A1A)
    EvAccentColor.ORANGE -> Color(0xFFB65D00)
    EvAccentColor.YELLOW -> Color(0xFF7D5700)
    EvAccentColor.GREEN -> Color(0xFF006D3B)
    EvAccentColor.TEAL -> Color(0xFF006A6A)
}

/** FontWeight 在 COUI 的 Typography 里是结构的一部分，这里只是保留引用避免未用告警。 */
private val unusedWeight: FontWeight = FontWeight.Normal
