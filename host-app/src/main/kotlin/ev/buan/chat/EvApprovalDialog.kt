package ev.buan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.Dialog
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import ev.buan.ApprovalCoordinator
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.ui.EvButton
import ev.buan.ui.EvButtonKind
import ev.buan.ui.EvColors
import ev.buan.ui.EvText
import ev.buan.ui.EvTone

/**
 * 危险操作的人工审批。
 *
 * 这是整条链上唯一"必须有人在"的一环：brain 会挂起等一个答案，
 * 而这个答案是**不执行**的主动作——三个动作里两个是拒绝方向。
 *
 * ## 用 COUI 的 Dialog，不再自己画遮罩
 *
 * 上一版是 `Box(黑 42% 遮罩) + surface1 圆角面板 + 三个自绘按钮`。问题是它在
 * 深色界面上会露出一块"发灰的方框"——没有材质、没有进出动画，看起来像贴在屏幕上的
 * 另一层界面，而不是从当前页面浮起来的。COUI 的 [Dialog] 自带玻璃材质、进出曲线、
 * 底部放置与按钮排布，这些东西自己画一万次也对不齐。
 *
 * ## 参数要露出来
 *
 * 只写"要执行 run_shell"等于没问。用户要判断的是**这一条命令**，
 * 所以参数原样铺开、等宽字体、可滚动：`rm -rf /sdcard/Download` 和 `ls` 完全是两件事。
 *
 * ## 默认动作是拒绝
 *
 * 点外面、按返回，一律等于拒绝。误触的代价必须偏向"什么都没发生"。
 */
@Composable
fun EvApprovalDialog(
    pending: State<ApprovalCoordinator.Pending?>,
    onDecide: (id: Long, ApprovalDecision) -> Unit,
) {
    val request = pending.value ?: return
    val colors = EvColors
    // 主操作必须自带配色：COUI 把 Primary 的颜色交给调用方，因为弹窗不知道宿主主题。
    val primaryColors = CouiDialogActionColors(
        surface = colors.accent,
        content = colors.onAccent,
        disabledSurface = colors.accent.copy(alpha = 0.3f),
        disabledContent = colors.onAccent.copy(alpha = 0.3f),
    )

    Dialog(
        visible = true,
        onDismissRequest = { onDecide(request.id, ApprovalDecision.DENY) },
        title = "需要你确认",
        message = "这个操作会改动设备或外部数据，模型不能自己决定。",
        confirmAction = CouiDialogAction(
            text = "允许一次",
            onClick = { onDecide(request.id, ApprovalDecision.ALLOW_ONCE) },
            style = CouiDialogActionStyle.Primary,
            colors = primaryColors,
        ),
        dismissAction = CouiDialogAction(
            text = "拒绝",
            onClick = { onDecide(request.id, ApprovalDecision.DENY) },
        ),
        content = {
            EvText(request.toolName, kind = CouiTextKind.Description, tone = EvTone.Accent)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(colors.surface0, CouiShape(12.dp))
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
            Spacer(Modifier.height(8.dp))
            // 第三个动作：COUI 的弹窗只有"主 + 次"两个位置，而且 `DialogButton` 是库内 internal，
            // 外部拼不出第三个。会话级放行用 EV 的次操作按钮——它底下就是 COUI 的按压反馈与形状，
            // 只是不占弹窗自己的按钮位。
            Box(Modifier.fillMaxWidth()) {
                EvButton(
                    "本次会话都允许",
                    onClick = { onDecide(request.id, ApprovalDecision.ALLOW_SESSION) },
                    kind = EvButtonKind.Secondary,
                )
            }
        },
    )
}
