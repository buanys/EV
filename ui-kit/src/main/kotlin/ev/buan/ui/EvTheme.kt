package ev.buan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * Applies the shared design system.
 *
 * The host owns this composition; plugins render inside it and therefore inherit the same
 * Miuix color scheme, typography and density without shipping any theme code of their own.
 */
@Composable
fun EvTheme(
    appearance: EvAppearance = EvAppearance(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        EvThemeMode.SYSTEM -> systemDark
        EvThemeMode.LIGHT -> false
        EvThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && isDark -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.Light
        isDark -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetLight
    }
    val keyColor = if (appearance.monetEnabled && appearance.accentColor != EvAccentColor.SYSTEM) {
        appearance.accentColor.seedColor()
    } else {
        null
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixStyle(),
            isDark = isDark,
        )
    }
    val colors = controller.currentColors()
    val themed = remember(colors, isDark, appearance.pureBlackEnabled) {
        if (appearance.pureBlackEnabled && isDark) {
            colors.copy(background = Color.Black, surface = Color.Black)
        } else {
            colors
        }
    }

    MiuixTheme(colors = themed) {
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
        ) {
            content()
        }
    }
}

internal fun EvPaletteStyle.toMiuixStyle(): ThemePaletteStyle = when (this) {
    EvPaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    EvPaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    EvPaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    EvPaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    EvPaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    EvPaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    EvPaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    EvPaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    EvPaletteStyle.CONTENT -> ThemePaletteStyle.Content
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
