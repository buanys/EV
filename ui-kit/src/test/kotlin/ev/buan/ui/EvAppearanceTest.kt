package ev.buan.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Appearance math is the only ui-kit logic that can be wrong without anyone noticing on screen:
 * a bad clamp produces a subtly broken layout instead of an exception.
 */
class EvAppearanceTest {

    @Test
    fun `缩放值被夹在合法区间`() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.1f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(9f))
        assertEquals(1.05f, normalizeInterfaceScale(1.05f))
    }

    @Test
    fun `非法缩放回落到默认值`() {
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `normalized 只改缩放不改其它字段`() {
        val appearance = EvAppearance(
            themeMode = EvThemeMode.DARK,
            monetEnabled = true,
            paletteStyle = EvPaletteStyle.VIBRANT,
            accentColor = EvAccentColor.TEAL,
            pureBlackEnabled = true,
            blurEnabled = false,
            interfaceScale = 5f,
        )
        val normalized = appearance.normalized()
        assertEquals(MAX_INTERFACE_SCALE, normalized.interfaceScale)
        assertEquals(appearance.copy(interfaceScale = MAX_INTERFACE_SCALE), normalized)
    }

    @Test
    fun `默认值处于合法区间`() {
        assertTrue(DEFAULT_INTERFACE_SCALE in MIN_INTERFACE_SCALE..MAX_INTERFACE_SCALE)
        assertEquals(DEFAULT_INTERFACE_SCALE, EvAppearance().normalized().interfaceScale)
    }

    @Test
    fun `枚举项数稳定`() {
        assertEquals(3, EvThemeMode.entries.size)
        assertEquals(9, EvPaletteStyle.entries.size)
        assertEquals(9, EvAccentColor.entries.size)
    }
}
