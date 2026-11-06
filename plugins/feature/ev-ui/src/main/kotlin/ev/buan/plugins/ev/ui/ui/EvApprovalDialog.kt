package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.ev.ui.ApprovalPrompt
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * The gate between a dangerous tool and the device: a modal the agent cannot dismiss.
 *
 * Rendered above the whole chat surface while [prompt] is non-null. Refusing stays the most
 * prominent action - it sits rightmost, where a hurried tap lands - but it is tinted `danger`, not
 * `accent`: the brand colour means "this is the good path", and refusing is not that (spec §1.4,
 * §7.2 `danger` 仅用于拒绝). The two allow paths stay quiet beside it.
 *
 * The scrim is black, never a white veil: on a dark theme a light overlay lifts the whole page to
 * grey and the app stops looking like itself for as long as the dialog is up.
 *
 * There is deliberately no outside-tap dismissal: an accidental touch must decide *nothing*, and
 * the request simply keeps waiting.
 */
@Composable
internal fun EvApprovalDialog(
    prompt: ApprovalPrompt,
    onDecide: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(EvThemeTokens.radii.largeShape)
                .background(tokens.surface2)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "高危操作待审批",
                style = EvThemeTokens.type.title.style(tokens.textPrimary),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "agent 请求执行 ${prompt.toolName}。执行前需要你的决定；放着不管就等于没有执行。",
                style = EvThemeTokens.type.caption.style(tokens.textSecondary),
            )
            if (prompt.argumentsJson.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(EvThemeTokens.radii.smallShape)
                        .background(tokens.surface1)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = prompt.argumentsJson,
                        style = EvThemeTokens.type.caption.style(tokens.textPrimary).copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                DecisionButton(
                    text = "本次运行不再询问",
                    danger = false,
                    label = "本次运行内放行该工具",
                ) { onDecide(ApprovalDecision.ALLOW_SESSION) }
                Spacer(Modifier.width(6.dp))
                DecisionButton(
                    text = "允许一次",
                    danger = false,
                    label = "允许执行这一次",
                ) { onDecide(ApprovalDecision.ALLOW_ONCE) }
                Spacer(Modifier.width(8.dp))
                DecisionButton(
                    text = "拒绝",
                    danger = true,
                    label = "拒绝本次执行",
                ) { onDecide(ApprovalDecision.DENY) }
            }
        }
    }
}

@Composable
private fun DecisionButton(
    text: String,
    danger: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(EvThemeTokens.radii.smallShape)
            .background(if (danger) tokens.danger.copy(alpha = 0.16f) else tokens.surface3)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = EvThemeTokens.type.label.style(
                if (danger) tokens.danger else tokens.textSecondary,
            ),
        )
    }
}
