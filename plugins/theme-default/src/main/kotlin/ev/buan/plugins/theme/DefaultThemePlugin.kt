package ev.buan.plugins.theme

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvIconSet
import ev.buan.ui.evThemeGlyph
import ev.buan.ui.EvGlassSpec
import ev.buan.ui.EvMotionSpec
import ev.buan.ui.EvRadiusTokens
import ev.buan.ui.EvThemeSpec
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ServiceDirectory
import org.cordis.api.ThemeService
import org.cordis.dependencies

/**
 * The global theme: the app's most fundamental visual definitions, shipped as their own plugin.
 *
 * Layout lives in ev-ui; what the app *looks like* lives here. Swapping this single plugin repaints
 * every screen - colour ladder, radii, type ramp, motion-adjacent glass parameters - without
 * touching a layout file. That split is the point: someone who does not like the shipped look
 * replaces this plugin, not the entire interface.
 *
 * Ships as a normal jar plugin under `EV/mods`; it is deliberately not bundled with the app, so an
 * install without it keeps the compiled-in fallback look.
 *
 * No permissions and no storage: a theme is pure data, and this plugin never reads or writes files.
 */
class DefaultThemePlugin : Plugin<Unit> {

    override val name = "theme-default"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val service = object : ThemeService {
            override fun themes(): List<EvThemeSpec> = Themes
        }
        directory.publish(ThemeService.Key, service)
        // Disabling the plugin retracts the provider in the same step that disposes it. The identity
        // check keeps this stale disposer from retracting a newer instance after a re-activation.
        effect.collect {
            if (directory.lookup(ThemeService.Key) === service) directory.retract(ThemeService.Key)
        }
    }

    private companion object {
        /**
         * The three looks this plugin offers.
         *
         * `ev-default` reproduces the compiled-in fallback exactly, so installing the plugin never
         * changes the app on its own - it only makes the definition replaceable. The other two are
         * the ones a user picks when they want a different shape language.
         */
        val Themes: List<EvThemeSpec> = listOf(
            EvThemeSpec.Default,
            EvThemeSpec(
                id = "ev-soft",
                name = "圆润",
                description = "更大的圆角，卡片更柔和",
                radii = EvRadiusTokens(small = 12.dp, medium = 20.dp, large = 28.dp),
                // Softer and slower: the whole point of a round theme is that nothing snaps.
                motion = EvMotionSpec(
                    gentleDamping = 0.9f,
                    gentleStiffness = 180f,
                    snappyDamping = 0.85f,
                    snappyStiffness = 380f,
                    fadeMillis = 220,
                ),
                // Round theme, round marks: the overflow cue becomes three larger discs.
                icons = EvIconSet.of(mapOf(EvGlyphKind.More to RoundMore)),
                glass = EvGlassSpec(
                    blurRadius = 32.dp,
                    tintAlpha = 0.5f,
                    highlightAlpha = 0.25f,
                    borderAlpha = 0.16f,
                ),
            ),
            EvThemeSpec(
                id = "ev-square",
                name = "方正",
                description = "小圆角，界面更利落",
                radii = EvRadiusTokens(small = 4.dp, medium = 6.dp, large = 8.dp),
                // Tighter and faster: squared-off shapes read as more mechanical, so the motion is.
                motion = EvMotionSpec(
                    gentleDamping = 0.75f,
                    gentleStiffness = 420f,
                    snappyDamping = 0.7f,
                    snappyStiffness = 900f,
                    fadeMillis = 110,
                ),
                // Square theme, square marks: the same cue as three hard squares.
                icons = EvIconSet.of(mapOf(EvGlyphKind.More to SquareMore)),
            ),
        )
    }
}

/**
 * Three discs, drawn as filled paths: the round theme's overflow cue.
 *
 * A theme redraws a mark with the same primitives the built-in set uses, so the two languages
 * stay compatible - this is a redraw, not a second icon system.
 */
private val RoundMore: ImageVector = evThemeGlyph("ThemeMoreRound") {
    fun disc(cx: Float) = path(fill = SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(cx - 1.7f, 12f)
        arcTo(1.7f, 1.7f, 0f, true, true, cx + 1.7f, 12f)
        arcTo(1.7f, 1.7f, 0f, true, true, cx - 1.7f, 12f)
        close()
    }
    disc(6.5f)
    disc(12f)
    disc(17.5f)
}

/** Three squares, same cue in the square theme's language. */
private val SquareMore: ImageVector = evThemeGlyph("ThemeMoreSquare") {
    fun square(cx: Float) =
        path(fill = SolidColor(androidx.compose.ui.graphics.Color.Black)) {
            moveTo(cx - 1.6f, 10.4f)
            lineTo(cx + 1.6f, 10.4f)
            lineTo(cx + 1.6f, 13.6f)
            lineTo(cx - 1.6f, 13.6f)
            close()
        }
    square(6.5f)
    square(12f)
    square(17.5f)
}
