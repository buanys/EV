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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.ev.ui.ApprovalPrompt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The gate between a dangerous tool and the device: a modal the agent cannot dismiss.
 *
 * Rendered above the whole chat surface while [prompt] is non-null. Refusing is the safe default
 * and gets the primary slot (filled, rightmost, where a hurried tap lands); the two allow paths
 * stay quiet text actions beside it. There is deliberately no outside-tap dismissal: an
 * accidental touch must decide *nothing*, and the request simply keeps waiting.
 *
 * Until agent-shell lands, nothing publishes dangerous tools, so this dialog is exercised only by
 * the gate's unit tests - it is here so the first dangerous tool needs no UI work.
 */
@Composable
internal fun EvApprovalDialog(
    prompt: ApprovalPrompt,
    onDecide: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "高危操作待审批",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "agent 请求执行 ${prompt.toolName}。执行前需要你的决定；放着不管就等于没有执行。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (prompt.argumentsJson.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.05f))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = prompt.argumentsJson,
                        style = MiuixTheme.textStyles.footnote1.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MiuixTheme.colorScheme.onBackground,
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
                    filled = false,
                    label = "本次运行内放行该工具",
                ) { onDecide(ApprovalDecision.ALLOW_SESSION) }
                DecisionButton(
                    text = "允许一次",
                    filled = false,
                    label = "允许执行这一次",
                ) { onDecide(ApprovalDecision.ALLOW_ONCE) }
                Spacer(Modifier.width(4.dp))
                // 拒绝 sits rightmost where a hurried tap lands, and filled so it reads as the
                // default: the safest choice must be the easiest one to make.
                DecisionButton(
                    text = "拒绝",
                    filled = true,
                    label = "拒绝本次执行",
                ) { onDecide(ApprovalDecision.DENY) }
            }
        }
    }
}

@Composable
private fun DecisionButton(
    text: String,
    filled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (filled) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.05f),
            )
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (filled) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
