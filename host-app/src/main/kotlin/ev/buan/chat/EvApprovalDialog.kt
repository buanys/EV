package ev.buan.chat

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ApprovalCoordinator
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.ui.EvColors

/**
 * 危险操作的人工审批。
 *
 * 这是整条链上唯一"必须有人在"的一环：brain 会挂起等一个答案，
 * 而这个答案是**不执行**的主动作——三个按钮里有两个是拒绝方向的。
 *
 * ## 参数要露出来
 *
 * 只写"要执行 run_shell"等于没问。用户要判断的是**这一条命令**，
 * 所以参数原样铺开、等宽字体、可滚动：`rm -rf /sdcard/Download` 和 `ls` 完全是两件事。
 *
 * ## 默认动作是拒绝
 *
 * 点遮罩、按返回，一律等于拒绝。误触的代价必须偏向"什么都没发生"。
 */
@Composable
fun EvApprovalDialog(
    pending: State<ApprovalCoordinator.Pending?>,
    onDecide: (id: Long, ApprovalDecision) -> Unit,
) {
    val request = pending.value ?: return
    val colors = EvColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            // 点空白 = 拒绝：误触只会让操作不发生。
            .clickable(indication = null, interactionSource = null) {
                onDecide(request.id, ApprovalDecision.DENY)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(colors.surface1, RoundedCornerShape(24.dp))
                .padding(20.dp)
                // 点面板本身不该关掉它，否则读参数时手一滑就拒了。
                .clickable(indication = null, interactionSource = null) {},
        ) {
            BasicText(
                text = "需要你确认",
                style = TextStyle(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = colors.textPrimary),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "这个操作会改动设备或外部数据，模型不能自己决定。",
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = colors.textTertiary),
            )
            Spacer(Modifier.height(14.dp))

            BasicText(
                text = request.toolName,
                style = TextStyle(fontSize = 14.sp, color = colors.accent, fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(colors.surface0, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BasicText(
                        text = request.argumentsJson,
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(
                    label = "拒绝",
                    weight = 1f,
                    background = colors.surface2,
                    textColor = colors.textSecondary,
                ) { onDecide(request.id, ApprovalDecision.DENY) }

                DialogButton(
                    label = "本次会话都允许",
                    weight = 1.6f,
                    background = colors.surface2,
                    textColor = colors.textSecondary,
                ) { onDecide(request.id, ApprovalDecision.ALLOW_SESSION) }

                DialogButton(
                    label = "允许一次",
                    weight = 1f,
                    background = colors.accent,
                    textColor = colors.onAccent,
                ) { onDecide(request.id, ApprovalDecision.ALLOW_ONCE) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DialogButton(
    label: String,
    weight: Float,
    background: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(42.dp)
            .background(background, RoundedCornerShape(21.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = TextStyle(fontSize = 13.sp, color = textColor))
    }
}
