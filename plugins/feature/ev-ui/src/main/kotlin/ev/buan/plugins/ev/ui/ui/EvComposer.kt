package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

object EvComposerDefaults {
    /** Resting capsule corner; active card uses the theme's large radius. */
    val RestingRadius = 28.dp
    val SidePadding = 16.dp
    val ActionSize = 36.dp

    /** Space held for the model chip at the end of the text field. */
    val ModelChipMax = 110.dp

    /** One picker row; the sheet is sized in multiples of it. */
    val SheetRow = 56.dp
    val ModelChipReserve = 116.dp
    val ActionGlyph = 17.dp
    /** The "+" is drawn one notch smaller: a cross fills its box, a mic does not. */
    val PlusGlyph = 16.dp
    val FieldMinResting = 24.dp

    /**
     * 展开后的**起始**高度 = 一行。
     *
     * 以前这里是 52.dp（约两行），于是输入框一获得焦点就先长到两行高，
     * 哪怕一个字都没打——用户看到的是"展开即最高"。输入框应该按内容长，
     * 最小高度只负责"空着的时候也有一行的高度"。
     *
     * 26.dp = body 的行高（16sp/26sp）。不按字号算是因为 Compose 的 sp→dp 会受
     * 系统字体缩放影响，而这个值是布局下限，取一个稳定的行长更可预期。
     */
    val FieldMinActive = 26.dp

    /**
     * 三行半。超过这个高度输入框内部滚动，不再把工具栏往下挤。
     *
     * 3.5 × 26 = 91，取 92 留 1dp 余量，免得最后半行被裁掉一个像素。
     */
    val FieldMaxActive = 92.dp

    /**
     * 输入框最多画几行，超过之后在内部滚动。
     *
     * 这个值必须和 [FieldMaxActive] 对齐：光靠外层 `heightIn` 是管不住 `BasicTextField` 的，
     * 它默认 `maxLines = Int.MAX_VALUE`，会按全文测量再把行画到容器外面去——容器没 clip，
     * 于是五行的草稿会把工具栏顶开、把消息压在下面。
     *
     * 92dp 减去内边距约 4 行（正文行高 ~22dp），取 4 正好在边界内。
     */
    const val FieldMaxLines = 4
    val PanelIconSize = 44.dp
    val PanelGlyph = 20.dp
}

/** One entry of the composer's `+` panel: a glyph, a caption, and where the tap goes. */
data class EvComposerAction(
    val kind: EvGlyphKind,
    val label: String,
    val onClick: () -> Unit,
)


/**
 * The two-state composer (spec §7.3).
 *
 * Resting it is a full-width capsule (`surface1`, full radius); focused or non-empty it grows into
 * a card (`surface2`, large radius) whose field takes the upper area while the button row sinks to
 * the bottom. The morph is one continuous motion - corner radius, container color and field height
 * all animate on the same snappy spring, and no element is swapped out for another.
 *
 * The send key has three faces at one fixed spot: mic (empty draft), send (accent disc), stop
 * (a turn is running). Faces crossfade with a spring scale; the disc color springs, never cuts.
 *
 * Keyboard relation: the caller keeps `imePadding` on this composable; while the IME animates the
 * composer only morphs shape and never runs its own translation, so the two animations never fight
 * over layout (historical keyboard-jump lesson, spec §7.3).
 */
@Composable
fun EvComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "交给 EV 去完成",
    onMic: (() -> Unit)? = null,
    /** Current model, shown as a chip left of the mic; null hides it. */
    modelName: String? = null,
    /** When false the caller paints the background (glass); the field only draws its content. */
    opaque: Boolean = true,
    onModelClick: () -> Unit = {},
    busy: Boolean = false,
    onStop: () -> Unit = {},
    plusActive: Boolean = false,
    onPlus: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    var focused by remember { mutableStateOf(false) }
    // Empty input: the card opens and closes with the keyboard. With text present it stays open -
    // collapsing under the cursor while typing is worse than a tall card.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val active = value.isNotEmpty() || imeVisible

    val radius by animateDpAsState(
        targetValue = if (active) EvThemeTokens.radii.large else EvComposerDefaults.RestingRadius,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerRadius",
    )
    val containerColor by animateColorAsState(
        targetValue = if (active) tokens.surface2 else tokens.surface1,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerColor",
    )
    val fieldMinHeight by animateDpAsState(
        targetValue = if (active) EvComposerDefaults.FieldMinActive else EvComposerDefaults.FieldMinResting,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerField",
    )

    // Two-state continuous morph (spec §7.3): the card is one layout at all times. Resting, the two
    // actions sit in the same row as the field; active, the field grows upward and the same two
    // instances slide down onto their own row. Nothing is created or destroyed on the way, which is
    // what made the previous version read as a hard cut.
    val actionSize = EvComposerDefaults.ActionSize
    val actionDrop by animateDpAsState(
        targetValue = if (active) EvComposerDefaults.FieldMinActive + 10.dp else 0.dp,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerActionDrop",
    )
    // Springs overshoot on purpose (spec §6.2), but padding() rejects negative values, so anything
    // that ends up as padding is clamped. Crash that proved it: "Padding must be non-negative" at
    // this file while the inset sprang back from 40dp to 2dp and crossed zero.
    val sideInset by animateDpAsState(
        targetValue = if (active) 2.dp else actionSize - 4.dp,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerSideInset",
    )
    val toolbarReserve by animateDpAsState(
        targetValue = if (active) actionSize + 4.dp else 0.dp,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "composerToolbarReserve",
    )
    val safeSideInset = sideInset.coerceAtLeast(0.dp)
    val safeToolbarReserve = toolbarReserve.coerceAtLeast(0.dp)
    // Width the model chip occupies at the end of the field, so the text stops before it.
    val modelReserve = if (modelName != null) EvComposerDefaults.ModelChipReserve else 0.dp

    Box(
        modifier.fillMaxWidth()
            .padding(horizontal = EvComposerDefaults.SidePadding, vertical = 10.dp)
            .clip(RoundedCornerShape(radius))
            .then(if (opaque) Modifier.background(containerColor) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // 文本区：只往上长，长到 4 行就自己滚动。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = fieldMinHeight, max = EvComposerDefaults.FieldMaxActive),
                contentAlignment = if (active) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = EvThemeTokens.type.body.style(tokens.textTertiary),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    // 超过 4 行就在这里滚动，而不是继续往外长：外层 heightIn 只是盒子，
                    // 没有 maxLines 的话文本会照画不误，把工具栏和消息一起顶掉。
                    maxLines = EvComposerDefaults.FieldMaxLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChange(it.isFocused)
                        },
                )
            }

            // 按钮行：**固定在卡片底部**，不随文本行数移动。
            //
            // 之前的做法是把三个按钮浮在整个 Box 上、按 active 状态给一个固定偏移（36dp）。
            // 文本一长到三四行，偏移还停在 36dp —— 按钮就悬在卡片中间，看起来像"跟着字走"。
            // 放进 Column 的最后一行之后，文本长多高都跟它无关。
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(EvComposerDefaults.ActionSize)
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlusButton(
                    active = plusActive,
                    onClick = onPlus,
                    modifier = Modifier,
                )
                Spacer(Modifier.weight(1f))
                if (modelName != null) {
                    ModelChip(
                        name = modelName,
                        onClick = onModelClick,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                SendKey(
                    value = value,
                    busy = busy,
                    onMic = onMic,
                    onSend = onSend,
                    onStop = onStop,
                    modifier = Modifier,
                )
            }
        }
    }
}


/**
 * The model picker, shaped like the `+` panel because it is the same kind of thing: a secondary
 * sheet that grows out of the composer and disappears when you pick something.
 */
@Composable
fun EvModelSheet(
    models: List<String>,
    current: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    // Five and a half rows: enough to see there is more, short enough to leave the conversation
    // visible. A picker that covers the screen is a page, not a sheet.
    val maxHeight = EvComposerDefaults.SheetRow * 5.5f
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = EvComposerDefaults.SidePadding)
            .padding(bottom = 8.dp)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        if (models.isEmpty()) {
            Text(
                text = "还没有可用的模型",
                style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        models.forEach { name ->
            val selected = name == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                    .clickable(onClickLabel = name) { onPick(name) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = EvThemeTokens.type.body.style(
                        if (selected) tokens.textPrimary else tokens.textSecondary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    EvGlyph(EvGlyphKind.Send, color = tokens.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * The `+` panel (spec §7.3): a card that grows upward out of the composer's spot, carrying the
 * L1 capabilities in one row of tiles. Rendered by the caller inside an `AnimatedVisibility`, so
 * the panel and the composer share the same vertical boundary while it springs open.
 */
@Composable
fun EvComposerPanel(
    actions: List<EvComposerAction>,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = EvComposerDefaults.SidePadding)
            .padding(bottom = 4.dp)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                    .clickable(onClickLabel = action.label, onClick = action.onClick)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(EvComposerDefaults.PanelIconSize)
                        .clip(CircleShape)
                        .background(tokens.surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    EvGlyph(action.kind, color = tokens.textSecondary, modifier = Modifier.size(EvComposerDefaults.PanelGlyph))
                }
                Text(
                    text = action.label,
                    style = EvThemeTokens.type.micro.style(tokens.textSecondary),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
