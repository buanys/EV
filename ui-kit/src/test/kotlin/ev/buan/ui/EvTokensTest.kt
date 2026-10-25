package ev.buan.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Token values are the contract every screen is about to be rebuilt against (docs/design/
 * EV-UI设计规范.md). A drifted ladder or a silently changed spring would degrade the whole
 * design system at once, so the invariants live here.
 */
class EvTokensTest {

    @Test
    fun `深色表面阶梯明度逐级递增`() {
        val colors = evDarkColorTokens()
        assertTrue(colors.surface0.luminance() < colors.surface1.luminance())
        assertTrue(colors.surface1.luminance() < colors.surface2.luminance())
        assertTrue(colors.surface2.luminance() < colors.surface3.luminance())
    }

    @Test
    fun `浅色表面阶梯明度逐级递减`() {
        val colors = evLightColorTokens()
        assertTrue(colors.surface0.luminance() > colors.surface1.luminance())
        assertTrue(colors.surface1.luminance() > colors.surface2.luminance())
        assertTrue(colors.surface2.luminance() > colors.surface3.luminance())
    }

    @Test
    fun `纯黑模式只压暗页面背景`() {
        val pureBlack = evDarkColorTokens(pureBlack = true)
        assertEquals(Color.Black, pureBlack.surface0)
        assertEquals(evDarkColorTokens().surface1, pureBlack.surface1)
    }

    @Test
    fun `accentSoft 继承色相只降透明度`() {
        val accent = Color(0xFF4C8DFF)
        val soft = evDarkColorTokens(accent = accent).accentSoft
        // sRGB colors pack into a Long with reduced channel precision, so channels round-trip
        // with tiny error — compare with tolerance, not bit-exact.
        assertEquals(accent.red, soft.red, 0.001f)
        assertEquals(accent.green, soft.green, 0.001f)
        assertEquals(accent.blue, soft.blue, 0.001f)
        assertEquals(0.16f, soft.alpha, 0.001f)
    }

    @Test
    fun `正文行高满足中文排版下限`() {
        val body = DefaultEvTypeTokens.body
        assertTrue(
            body.lineHeight.value / body.fontSize.value >= 1.6f,
            "body 行高/字号必须 >= 1.6（规范 §2.1）",
        )
    }

    @Test
    fun `间距与圆角阶梯严格递增`() {
        val spacing = DefaultEvSpacingTokens
        val ladder = listOf(spacing.xs, spacing.s, spacing.m, spacing.l, spacing.xl, spacing.xxl, spacing.xxxl)
        assertEquals(ladder.sorted(), ladder)

        val radii = DefaultEvRadiusTokens
        assertTrue(radii.small < radii.medium)
        assertTrue(radii.medium < radii.large)
    }

    @Test
    fun `三组弹簧参数符合规范`() {
        val gentle = EvMotionTokens.gentle<Float>()
        assertEquals(0.8f, gentle.dampingRatio)
        assertEquals(300f, gentle.stiffness)

        val snappy = EvMotionTokens.snappy<Float>()
        assertEquals(0.75f, snappy.dampingRatio)
        assertEquals(600f, snappy.stiffness)

        val bouncy = EvMotionTokens.bouncy<Float>()
        assertEquals(0.6f, bouncy.dampingRatio)
        assertEquals(400f, bouncy.stiffness)
    }

    @Test
    fun `fadeFast 为 150ms 线性`() {
        assertEquals(EvMotionTokens.FADE_FAST_MILLIS, EvMotionTokens.fadeFast().durationMillis)
        assertEquals(150, EvMotionTokens.FADE_FAST_MILLIS)
    }

    @Test
    fun `减弱动效时任何规格降级为瞬时切换`() {
        val custom = spring<Float>(dampingRatio = 0.5f, stiffness = 100f)
        assertIs<SnapSpec<*>>(EvMotionTokens.reducedAware(true) { custom })
        assertSame(custom, EvMotionTokens.reducedAware(false) { custom })
    }

    @Test
    fun `外观默认不减弱动效且 normalized 保留该字段`() {
        assertEquals(false, EvAppearance().reducedMotionEnabled)
        val appearance = EvAppearance(reducedMotionEnabled = true, interfaceScale = 5f)
        assertEquals(true, appearance.normalized().reducedMotionEnabled)
    }
}
