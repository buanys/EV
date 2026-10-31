package ev.buan.plugins.ev.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import ev.buan.plugins.ev.ui.ui.EvComposer
import ev.buan.plugins.ev.ui.ui.EvComposerAction
import ev.buan.plugins.ev.ui.ui.EvComposerPanel
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens

/**
 * The composer and the `+` panel above it.
 *
 * The panel grows upward out of the composer's own slot, so the two share one vertical boundary
 * while it springs open (spec §7.3). Every way out is a callback: this file never decides where a
 * tap goes, it only reports it, which is what let the surface lose its layout code.
 */
@Composable
internal fun EvComposerArea(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    busy: Boolean,
    onStop: () -> Unit,
    plusActive: Boolean,
    onPlus: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = plusActive,
        enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeIn(EvMotionTokens.fadeFast()),
        exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeOut(EvMotionTokens.fadeFast()),
    ) {
        EvComposerPanel(
            actions = listOf(
                EvComposerAction(EvGlyphKind.Globe, "浏览器", onOpenBrowser),
                EvComposerAction(EvGlyphKind.Tool, "工具", onOpenTools),
                EvComposerAction(EvGlyphKind.Person, "角色", onOpenCharacters),
                EvComposerAction(EvGlyphKind.Sparkle, "Skills", onOpenSkills),
            ),
        )
    }

    EvComposer(
        value = draft,
        onValueChange = onDraftChange,
        onSend = onSend,
        placeholder = placeholder,
        busy = busy,
        onStop = onStop,
        plusActive = plusActive,
        onPlus = onPlus,
        onFocusChange = onFocusChange,
        modifier = Modifier.imePadding(),
    )
}
