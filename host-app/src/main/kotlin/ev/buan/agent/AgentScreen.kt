package ev.buan.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ui.EvButton
import ev.buan.ui.EvButtonKind
import ev.buan.ui.EvColors
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvTone
import org.cordis.api.AgentRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cordis.api.ServiceDirectory

/**
 * Agent 运行时管理页。
 *
 * 这一页只做一件事：把"它现在怎么跑"摊开。进程是哪一个文件、跑多久了、协议版本、
 * 这一轮允许多少次模型调用、系统提示是什么、上一次为什么失败——都能看见，也都能改。
 *
 * 数据全部来自主循环插件发布的 [AgentRuntime]；页面自己不缓存状态，每秒重读一次。
 * 这样页面上写的永远是进程的真实情况，而不是宿主对它的印象。
 */
@Composable
fun AgentScreen(services: ServiceDirectory?, onBack: () -> Unit) {
    val control = services?.lookup(AgentRuntime.Key)
    var tick by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draftPreamble by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick += 1
        }
    }

    val status = remember(tick) { control?.status() }

    EvPage(title = "Agent", onBack = onBack) {
        if (control == null || status == null) {
            EvSection(title = "运行时", footnote = "主循环插件没有发布管理面。") {
                EvRow(title = "不可用", summary = "装上并启用「主循环」后这里才有内容")
            }
            return@EvPage
        }

        EvSection(title = "进程", footnote = "它只通过管道和宿主说话，没有网络连接。") {
            EvRow(
                title = if (status.alive) "运行中" else "未启动",
                summary = status.binaryPath ?: "找不到 libev_agentd.so",
                trailing = {
                    EvText(
                        if (status.alive) "PID ${status.pid ?: "?"}" else "空闲",
                        kind = EvTextKind.Caption,
                        tone = if (status.alive) EvTone.Accent else EvTone.Muted,
                    )
                },
            )
            status.binaryBytes?.let { bytes ->
                EvRow(title = "二进制", summary = "native library，随宿主 APK 分发", trailing = {
                    EvText(formatBytes(bytes), kind = EvTextKind.Caption, tone = EvTone.Muted)
                })
            }
            EvRow(
                title = "已运行",
                summary = status.protocolVersion?.let { "协议 v$it" } ?: "还没握手",
                trailing = {
                    EvText(
                        status.startedAtMillis?.let { uptime(it) } ?: "—",
                        kind = EvTextKind.Caption,
                        tone = EvTone.Muted,
                    )
                },
            )
        }

        EvSection(title = "参数", footnote = "改完下一轮生效，不用重启进程。") {
            EvRow(
                title = "模型调用上限",
                summary = "一轮里最多问模型几次",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EvButton("−", kind = EvButtonKind.Secondary, size = ev.buan.ui.EvButtonSize.Compact, onClick = {
                            control.setMaxTurns(status.maxTurns - 1)
                            tick += 0
                            note@{ notice = "上限改为 ${control.status().maxTurns} 次" }
                        })
                        EvText("${status.maxTurns}", kind = EvTextKind.Caption, tone = EvTone.Accent)
                        EvButton("+", kind = EvButtonKind.Secondary, size = ev.buan.ui.EvButtonSize.Compact, onClick = {
                            control.setMaxTurns(status.maxTurns + 1)
                            notice = "上限改为 ${control.status().maxTurns} 次"
                        })
                    }
                },
            )
            val preambleValue = draftPreamble ?: status.preamble ?: ""
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EvText("系统提示（留空用内置那段）", kind = EvTextKind.Caption, tone = EvTone.Muted)
                BasicTextField(
                    value = preambleValue,
                    onValueChange = { draftPreamble = it },
                    textStyle = TextStyle(color = EvColors.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(EvColors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(EvColors.surface2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 4,
                    decorationBox = { inner ->
                        if (preambleValue.isEmpty()) {
                            EvText("你是运行在用户手机上的助手……", kind = EvTextKind.Caption, tone = EvTone.Muted)
                        }
                        inner()
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvButton("保存提示", kind = EvButtonKind.Secondary, onClick = {
                        control.setPreamble(draftPreamble)
                        draftPreamble = null
                        notice = "已保存，下一轮生效"
                    })
                    EvButton("恢复默认", kind = EvButtonKind.Secondary, onClick = {
                        control.setPreamble(null)
                        draftPreamble = null
                        notice = "已恢复内置提示"
                    })
                }
            }
        }

        EvSection(title = "操作", footnote = "重启会在下一次对话时重新起一个干净进程。") {
            EvRow(
                title = "重启进程",
                summary = "丢掉当前进程，历史不受影响（历史在会话日志里）",
                onClick = {
                    scope.launch {
                        control.restart()
                        notice = "进程已停止，下一轮会重新启动"
                        tick += 1
                    }
                },
                trailing = { EvText("重启", kind = EvTextKind.Caption, tone = EvTone.Accent) },
            )
            EvRow(
                title = "停止进程",
                summary = "不发消息就一直不会起来",
                onClick = {
                    scope.launch {
                        control.stop()
                        notice = "已停止"
                        tick += 1
                    }
                },
                trailing = { EvText("停止", kind = EvTextKind.Caption, tone = EvTone.Danger) },
            )
        }

        status.lastError?.let { error ->
            EvSection(title = "最近一次失败") {
                EvText(error, kind = EvTextKind.Caption, tone = EvTone.Danger)
            }
        }

        notice?.let { text ->
            EvText(
                text,
                kind = EvTextKind.Caption,
                tone = EvTone.Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun uptime(startedAt: Long): String {
    val seconds = (System.currentTimeMillis() - startedAt) / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ${seconds % 60}s"
    return "${minutes / 60}h ${minutes % 60}m"
}
