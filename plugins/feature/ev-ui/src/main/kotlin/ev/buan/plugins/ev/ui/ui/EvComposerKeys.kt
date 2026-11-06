package ev.buan.plugins.ev.ui.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * 输入框一侧的三个按键：模型胶囊、`+`、发送键。
 *
 * 它们从 `EvComposer` 拆出来，因为主 Composable 回答的是"这个输入框现在是什么形态"，
 * 而这三个回答的是"点下去发生什么"。发送键尤其如此：同一个位置有三种面孔
 * （麦克风 / 发送 / 停止），切换规则和动画全在它自己身上。
 */


/** The three faces of the send key, in muscle-memory order. */
internal enum class SendFace { Mic, Send, Stop }

/** The current model, as a small pill left of the mic. */
@Composable
internal fun ModelChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = EvThemeTokens.colors
    Text(
        text = name,
        style = EvThemeTokens.type.micro.style(tokens.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .height(EvComposerDefaults.ActionSize)
            .widthIn(max = EvComposerDefaults.ModelChipMax)
            .clip(CircleShape)
            .background(tokens.surface3)
            .clickable(onClickLabel = "切换模型", onClick = onClick)
            .padding(horizontal = 12.dp)
            .wrapContentHeight(),
    )
}

/** The `+` that opens the L1 panel; it rotates into a × while the panel is up. */
@Composable
internal fun PlusButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
internal fun SendKey(
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
