package ev.buan.plugins.eta.ui.ui

import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

object EvComposerDefaults {
    val Radius = 28.dp
    val SidePadding = 16.dp
}

/**
 * The composer from Eta's home screen: one large rounded panel that holds the prompt and a control
 * row (+ / model / mic / voice / send), with a soft glow bleeding out of its lower edge.
 *
 * Controls that have no behaviour yet are rendered but inert on purpose - hiding them would make
 * the layout look different from the design for no gain.
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
) {
    val primary = MiuixTheme.colorScheme.primary
    val canSend = value.isNotBlank()
    Column(modifier.fillMaxWidth().padding(horizontal = EvComposerDefaults.SidePadding, vertical = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(EvComposerDefaults.Radius))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MiuixTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                            MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .border(
                    width = 0.75.dp,
                    color = primary.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(EvComposerDefaults.Radius),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 16.sp),
                    cursorBrush = SolidColor(primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(EvGlyphKind.Plus, subdued = true, onClick = {})
                    Spacer(Modifier.width(10.dp))
                    RoundButton(EvGlyphKind.Atom, subdued = false, tinted = true, onClick = {})
                    Spacer(Modifier.weight(1f))
                    if (onMic != null) {
                        RoundButton(EvGlyphKind.Mic, subdued = true, onClick = onMic)
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        EvGlyph(EvGlyphKind.Atom, color = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    if (busy) {
                        // While a turn runs the send affordance becomes a stop button: same spot,
                        // same size, so the muscle memory that sends can also halt.
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(primary)
                                .clickable(onClickLabel = "停止生成", onClick = onStop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MiuixTheme.colorScheme.onPrimary),
                            )
                        }
                    } else {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) primary
                                else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                            )
                            .clickable(enabled = canSend, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        EvGlyph(
                            EvGlyphKind.Send,
                            color = if (canSend) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    kind: EvGlyphKind,
    subdued: Boolean,
    tinted: Boolean = false,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                when {
                    tinted -> primary.copy(alpha = 0.18f)
                    subdued -> MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvGlyph(
            kind,
            color = if (tinted) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(17.dp),
        )
    }
}
