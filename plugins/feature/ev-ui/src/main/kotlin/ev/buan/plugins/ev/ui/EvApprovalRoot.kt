package ev.buan.plugins.ev.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.ev.ui.ui.EvApprovalDialog

/**
 * 对话界面外面那一层：内容 + 审批对话框。
 *
 * 审批框挂在这一层而不是 [ChatSurface] 里面，因为它必须**盖住一切**：设置、工具、记忆、
 * 浏览器这些面板都会让 ChatSurface 提前返回，但一次危险调用可以在任何一个面板开着的时候
 * 到达，而人必须看见这个提问。
 */
@Composable
internal fun EvApprovalRoot(approvalGate: ApprovalGate, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        approvalGate.pending.firstOrNull()?.let { prompt ->
            EvApprovalDialog(
                prompt = prompt,
                onDecide = { decision -> approvalGate.decide(prompt.id, decision) },
            )
        }
    }
}
