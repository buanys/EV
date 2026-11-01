package ev.buan.plugins.ev.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import ev.buan.ui.evGlassLayer
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
import ev.buan.plugins.ev.ui.ui.EvModelSheet
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
private fun EvComposerPanels(
    plusActive: Boolean,
    onOpenBrowser: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenSkills: () -> Unit,
    modelSheetOpen: Boolean,
    models: List<String>,
    modelName: String?,
    onPickModel: (String) -> Unit,
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

    // Same boundary as the `+` panel: the sheet belongs to the composer, not to a dialog.
    androidx.compose.animation.AnimatedVisibility(
        visible = modelSheetOpen,
        enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeIn(EvMotionTokens.fadeFast()),
        exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeOut(EvMotionTokens.fadeFast()),
    ) {
        EvModelSheet(models = models, current = modelName, onPick = onPickModel)
    }

}

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
    modelName: String?,
    models: List<String>,
    modelSheetOpen: Boolean,
    onModelClick: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    // One sheet, not three cards: the glass wraps the panels and the field together, so an open
    // panel reads as the composer growing rather than as a second surface landing on top of it.
    val sheetShape = RoundedCornerShape(
        topStart = EvThemeTokens.radii.large,
        topEnd = EvThemeTokens.radii.large,
        bottomStart = EvThemeTokens.radii.large,
        bottomEnd = EvThemeTokens.radii.large,
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Box(Modifier.matchParentSize().evGlassLayer(sheetShape))
        Column {
            EvComposerPanels(
                plusActive = plusActive,
                onOpenBrowser = onOpenBrowser,
                onOpenTools = onOpenTools,
                onOpenCharacters = onOpenCharacters,
                onOpenSkills = onOpenSkills,
                modelSheetOpen = modelSheetOpen,
                models = models,
                modelName = modelName,
                onPickModel = onPickModel,
            )
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
        modelName = modelName,
        opaque = false,
        onModelClick = onModelClick,
            modifier = Modifier.imePadding(),
        )
        }
    }
}
