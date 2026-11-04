package ev.buan.plugins.ev.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import ev.buan.ui.evGlassLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.systemBars
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

    // 排障：三个 inset 一起打。ime=0 只说明键盘没弹（或者没传进来），
    // 但 nav/systemBars 也是 0 的话，就是整条 inset 分发链断了——那时改 padding 组合全都没用。
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val sysBottom = WindowInsets.systemBars.getBottom(density)
    androidx.compose.runtime.LaunchedEffect(imeBottom, navBottom, sysBottom) {
        android.util.Log.i("EV", "insets: ime=$imeBottom nav=$navBottom sys=$sysBottom px")
    }
    // 玻璃和间距都在**这一层**：`evGlassLayer` 画的是本 Box 的矩形，如果把 padding 加在内层
    // EvComposer 上，内容会缩进去而玻璃仍然贴着屏幕底——看起来就像"只有按钮行有间距"。
    Box(
        Modifier
            .fillMaxWidth()
            // 键盘和底部手势条取并集，不是串联：串联会把两个高度加起来。
            // navigationBars 在这里已被父级的 evSystemBarsPadding 消费成 0，
            // 所以键盘收起时真正起作用的是下面的 14dp——视觉呼吸，不是安全区。
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = 10.dp, end = 10.dp, bottom = 14.dp),
    ) {
        // The field floats over the transcript, so it needs more body than the drawer's glass:
        // 30% of the theme blur (a hint of what is underneath, not a readable copy of it) and a
        // denser tint, so the placeholder and the cursor never compete with a message scrolling by.
        Box(
            Modifier.matchParentSize()
                .evGlassLayer(sheetShape, tintAlpha = 0.72f, blurScale = 0.3f),
        )
        Column {
            EvComposerPanels(
                plusActive = plusActive,
                onOpenBrowser = onOpenBrowser,
                onOpenTools = onOpenTools,
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
            // 间距已经在外层（玻璃那一层）处理，这里不再重复加，否则内容会比玻璃多缩一段。
            modifier = Modifier,
        )
        }
    }
}
