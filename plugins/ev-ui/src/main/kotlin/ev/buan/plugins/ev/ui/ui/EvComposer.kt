package ev.buan.plugins.ev.ui.ui

import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

object EvComposerDefaults {
    /** Resting capsule corner; active card uses the theme's large radius. */
    val RestingRadius = 28.dp
    val SidePadding = 16.dp
    val ActionSize = 36.dp
    val ActionGlyph = 17.dp
    /** The "+" is drawn one notch smaller: a cross fills its box, a mic does not. */
    val PlusGlyph = 16.dp
    val FieldMinResting = 24.dp
    /**
     * Height of the expanded card (spec §7.3): the field owns the upper part and the toolbar sinks
     * to the bottom edge, so this has to stay tall enough to read as a multi-line input area.
     */
    val FieldMinActive = 52.dp

    /** Three and a half lines: past this the field scrolls instead of pushing the toolbar away. */
    val FieldMaxActive = 76.dp
    val PanelIconSize = 44.dp
    val PanelGlyph = 20.dp
}

/** One entry of the composer's `+` panel: a glyph, a caption, and where the tap goes. */
data class EvComposerAction(
    val kind: EvGlyphKind,
    val label: String,
    val onClick: () -> Unit,
)

/** The three faces of the send key, in muscle-memory order. */
private enum class SendFace { Mic, Send, Stop }

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

    Box(
        modifier.fillMaxWidth()
            .padding(horizontal = EvComposerDefaults.SidePadding, vertical = 10.dp)
            .clip(RoundedCornerShape(radius))
            .background(containerColor),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .padding(bottom = safeToolbarReserve),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = fieldMinHeight, max = EvComposerDefaults.FieldMaxActive)
                    .padding(start = safeSideInset, end = safeSideInset),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChange(it.isFocused)
                        },
                )
            }
        }
        PlusButton(
            active = plusActive,
            onClick = onPlus,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 2.dp, y = actionDrop),
        )
        SendKey(
            value = value,
            busy = busy,
            onMic = onMic,
            onSend = onSend,
            onStop = onStop,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = actionDrop),
        )
    }
}

/** The `+` that opens the L1 panel; it rotates into a × while the panel is up. */
@Composable
private fun PlusButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    val rotation by animateFloatAsState(
        targetValue = if (active) 45f else 0f,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "plusRotation",
    )
    val tint by animateColorAsState(
        targetValue = if (active) tokens.accent else tokens.textSecondary,
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "plusTint",
    )
    Box(
        modifier
            .size(EvComposerDefaults.ActionSize)
            .clip(CircleShape)
            .clickable(onClickLabel = "更多功能", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvGlyph(
            EvGlyphKind.Plus,
            color = tint,
            // Same optical size as the mic on the other end: the "+" is a convenience, and at +3dp
            // it was the brightest, largest thing inside an otherwise quiet composer.
            modifier = Modifier.size(EvComposerDefaults.PlusGlyph).graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * Send / mic / stop at one fixed 36dp spot: the muscle memory that sends can also halt, and an
 * empty draft offers the voice key instead of a disabled arrow.
 */
@Composable
private fun SendKey(
    value: String,
    busy: Boolean,
    onMic: (() -> Unit)?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    val face = when {
        busy -> SendFace.Stop
        value.isNotBlank() -> SendFace.Send
        else -> SendFace.Mic
    }
    val disc by animateColorAsState(
        targetValue = when (face) {
            // Quiet speech icon, not a filled disc: on an idle composer the only thing allowed to
            // look solid is the primary action, and sending nothing is not an action yet (§7.3).
            SendFace.Mic -> Color.Transparent
            else -> tokens.accent
        },
        animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.snappy() },
        label = "sendDisc",
    )
    Box(
        modifier
            .size(EvComposerDefaults.ActionSize)
            .clip(CircleShape)
            .background(disc)
            .clickable(
                enabled = face != SendFace.Mic || onMic != null,
                onClickLabel = when (face) {
                    SendFace.Mic -> "语音输入"
                    SendFace.Send -> "发送"
                    SendFace.Stop -> "停止生成"
                },
                onClick = when (face) {
                    SendFace.Mic -> onMic ?: {}
                    SendFace.Send -> onSend
                    SendFace.Stop -> onStop
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = face,
            transitionSpec = {
                (fadeIn(EvMotionTokens.fadeFast()) + scaleIn(EvMotionTokens.reducedAware<Float>(reduced) { EvMotionTokens.bouncy() }))
                    .togetherWith(fadeOut(EvMotionTokens.fadeFast()) + scaleOut(EvMotionTokens.reducedAware<Float>(reduced) { EvMotionTokens.bouncy() }))
            },
            label = "sendFace",
        ) { current ->
            when (current) {
                SendFace.Mic -> EvGlyph(
                    EvGlyphKind.Mic,
                    color = tokens.textSecondary,
                    modifier = Modifier.size(EvComposerDefaults.ActionGlyph),
                )

                SendFace.Send -> EvGlyph(
                    EvGlyphKind.Send,
                    color = tokens.onAccent,
                    modifier = Modifier.size(EvComposerDefaults.ActionGlyph),
                )

                SendFace.Stop -> Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(tokens.onAccent),
                )
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
            .clip(RoundedCornerShape(EvThemeTokens.radii.large))
            .background(tokens.surface2)
            .padding(vertical = 16.dp),
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
