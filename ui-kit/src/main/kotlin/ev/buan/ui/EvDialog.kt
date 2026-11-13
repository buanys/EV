package ev.buan.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.Dialog

/**
 * 确认类弹窗。
 *
 * 用 COUI 的 [Dialog]，因此玻璃材质、进出动画、按钮排布都是现成的——
 * 上一版自己画遮罩 + 面板，既没有材质也没有过渡。
 *
 * 两条调用约定：
 * 1. **主操作必须给配色**。COUI 强制 `Primary` 风格必须由宿主提供
 *    [CouiDialogActionColors]，因为弹窗不知道调用方主题的强调色。这里从 token 取。
 * 2. **默认动作是取消**。点外面、按返回都当取消：误触的代价必须偏向"什么都没发生"。
 */
@Composable
fun EvDialog(
    visible: Boolean,
    title: String? = null,
    message: String? = null,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = "取消",
    /** 危险操作确认时把主按钮改成危险色。 */
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    if (!visible) return
    val colors = EvColors

    val accent = if (danger) colors.danger else colors.accent
    val primaryColors = CouiDialogActionColors(
        surface = accent,
        content = colors.onAccent,
        disabledSurface = accent.copy(alpha = 0.3f),
        disabledContent = colors.onAccent.copy(alpha = 0.3f),
    )

    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        confirmAction = CouiDialogAction(
            text = confirmLabel,
            onClick = onConfirm,
            style = CouiDialogActionStyle.Primary,
            colors = primaryColors,
        ),
        dismissAction = dismissLabel?.let { CouiDialogAction(text = it, onClick = onDismiss) },
        content = content,
    )
}
