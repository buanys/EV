package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * 插件设置项的编辑器。
 *
 * 只有一种形状：**一个值，一个保存**。文本域和固定选项走同一个对话框，
 * 因为对用户来说这两件事没有区别——"这一项现在是什么、我想让它变成什么"。
 * 分成两个对话框会让设置页在两种控件之间来回跳，读起来像两种不同的操作。
 *
 * 没有"应用"按钮：点了保存就写，写失败由设置页自己报（它拿得到 [onConfirm] 的结果）。
 */
@Composable
internal fun ValueDialog(
    title: String,
    options: List<String>,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val tokens = EvThemeTokens.colors
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(EvThemeTokens.radii.large))
                .background(tokens.surface2)
                .padding(20.dp),
        ) {
            Text(text = title, style = EvThemeTokens.type.title.style(tokens.textPrimary))
            Spacer(Modifier.height(12.dp))
            if (options.isEmpty()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(tokens.surface1)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                            .clickable { text = option }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = EvThemeTokens.type.body.style(
                                if (option == text) tokens.textPrimary else tokens.textSecondary,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    style = EvThemeTokens.type.label.style(tokens.textSecondary),
                    modifier = Modifier
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "保存",
                    style = EvThemeTokens.type.label.style(tokens.onAccent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(tokens.accent)
                        .clickable { onConfirm(text) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
