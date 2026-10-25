package ev.buan.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE) else DEFAULT_INTERFACE_SCALE

/**
 * Visual preferences of the whole app. Kept as plain data so it can be persisted as JSON and
 * passed through a CompositionLocal; plugins read it but never own it.
 */
data class EvAppearance(
    val themeMode: EvThemeMode = EvThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val paletteStyle: EvPaletteStyle = EvPaletteStyle.TONAL_SPOT,
    val accentColor: EvAccentColor = EvAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    /**
     * Reduce all motion to instant switches (spec §6.4). The host should default this to the
     * system "remove animations" accessibility setting; the user toggle overrides it.
     */
    val reducedMotionEnabled: Boolean = false,
) {
    fun normalized(): EvAppearance = copy(interfaceScale = normalizeInterfaceScale(interfaceScale))
}

enum class EvThemeMode { SYSTEM, LIGHT, DARK }

enum class EvPaletteStyle {
    TONAL_SPOT, NEUTRAL, VIBRANT, EXPRESSIVE, RAINBOW, FRUIT_SALAD, MONOCHROME, FIDELITY, CONTENT
}

enum class EvAccentColor {
    SYSTEM, BLUE, PURPLE, PINK, RED, ORANGE, YELLOW, GREEN, TEAL
}

internal val LocalEvAppearance = staticCompositionLocalOf { EvAppearance() }
internal val LocalBlurEnabled = staticCompositionLocalOf { true }
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
