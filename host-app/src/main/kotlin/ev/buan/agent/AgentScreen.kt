package ev.buan.agent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.slider.Slider
import com.itos.coui.text.CouiTextKind
import ev.buan.ui.EvDivider
import ev.buan.ui.EvNotice
import ev.buan.ui.NoticeRow
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTone
import ev.buan.ui.EvTextField
import org.cordis.api.AgentRuntime
import org.cordis.api.ServiceDirectory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent 运行时管理页。
 *
 * 这一页只做一件事：把"它现在怎么跑"摊开。进程状态、上下文规模、两个参数、
 * 两个动作——全走 [EvSection]/[EvRow]，和设置页同一套语言。
 *
 * 数据全部来自主循环插件发布的 [AgentRuntime]；页面自己不缓存状态，每秒重读一次。
 * 这样页面上写的永远是进程的真实情况，而不是宿主对它的印象。
 *
 * 上一版的问题（截图里看得见的）：
 * - 路径塞在 summary 里，三行小字占掉半张卡片——它是调试信息，不是第一眼要读的；
 * - 系统提示的输入框和"保存/恢复"按钮自绘，圆角和 COUI 差半个像素；
 * - 状态右侧写"空闲"，靠文字表达状态。
 */
@Composable
fun AgentScreen(services: ServiceDirectory?, sessionId: String, onBack: () -> Unit) {
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
    // 上下文现状：这一轮模型实际会看到的，就是这几十条消息。
    var context by remember { mutableStateOf(Triple(0, 0, 0)) }
    LaunchedEffect(tick, sessionId) {
        val log = services?.lookup(ev.buan.plugins.agent.api.SessionLog.Key)
        val messages = runCatching { log?.messages(sessionId) }.getOrNull().orEmpty()
        context = Triple(
            messages.size,
            messages.sumOf { it.content.length },
            messages.count { it.role == ev.buan.plugins.agent.api.AgentRole.TOOL },
        )
    }

    EvPage(title = "Agent", onBack = onBack) {
        if (control == null || status == null) {
            EvSection(title = "运行时", footnote = "主循环插件没有发布管理面。") {
                EvRow(title = "不可用", summary = "装上并启用「主循环」后这里才有内容")
            }
            return@EvPage
        }

        // ---------------------------------------------------------------- 进程
        // 状态用色点：和侧边栏的"已连接"同一语言。路径收进"二进制"行——
        // 它属于"这份程序是什么"，不属于"它现在跑没跑"。
        EvSection(title = "进程", footnote = "它只通过管道和宿主说话，没有网络连接。") {
            EvRow(
                title = if (status.alive) "运行中" else "未启动",
                summary = if (status.alive) {
                    uptime(status.startedAtMillis ?: 0).let { "运行 $it" }
                } else {
                    "空闲；发消息时自动启动"
                },
                trailing = {
                    if (status.alive) {
                        EvText("PID ${status.pid}", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
                    }
                },
            )
            EvDivider()
            EvRow(
                title = "二进制",
                summary = "native library，随宿主 APK 分发（${status.binaryBytes?.let(::formatBytes) ?: "?"}）",
                trailing = status.protocolVersion?.let {
                    { EvText("协议 v$it", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted) }
                } ?: {},
            )
        }

        // -------------------------------------------------------------- 上下文
        EvSection(title = "上下文", footnote = "模型下一轮实际会看到的就是这些。") {
            EvRow(
                title = "会话 $sessionId",
                summary = "${context.first} 条消息 · ${context.third} 条工具结果",
                trailing = { EvText(formatBytes(context.second.toLong()), kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted) },
            )
        }

        // ---------------------------------------------------------------- 参数
        EvSection(title = "参数", footnote = "改完下一轮生效，不用重启进程。") {
            EvRow(
                title = "模型调用上限",
                summary = "一轮里最多问模型几次 · 当前 ${status.maxTurns}",
                trailing = { EvText("上限 20", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted) },
            )
            // 调数值用 COUI 的 Slider，不用 −/+ 按钮：
            // 按钮一次一档，从 4 调到 12 要点八下；滑块一次到位，而且当前位置就是当前值。
            Slider(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                status.maxTurns.toFloat(),
                { value ->
                    val turns = value.toInt()
                    if (turns != status.maxTurns) {
                        control.setMaxTurns(turns)
                        notice = "上限改为 $turns 次"
                    }
                },
                valueRange = 1f..20f,
                steps = 18,
            )
            EvDivider()
            // 字段区整体缩进：Group 的行有自带的图标/文字起笔边距，
            // 但这里放的是整块控件，不缩进就会顶着屏幕边——
            // 卡片是圆角的，内容却顶到直边，一眼就是毛躁。
            val fieldInset = 16.dp
            val preambleValue = draftPreamble ?: status.preamble ?: ""
            EvText(
                "系统提示",
                kind = CouiTextKind.PreferenceTitle,
                tone = EvTone.Normal,
                modifier = Modifier.padding(start = fieldInset, top = 8.dp, bottom = 4.dp),
            )
            EvTextField(
                value = preambleValue,
                onValueChange = { draftPreamble = it },
                placeholder = "留空用内置那段（你是运行在用户手机上的助手……）",
                minLines = 3,
                modifier = Modifier.padding(horizontal = fieldInset),
            )
            NoticeRow("保存提示", "恢复默认", modifier = Modifier.padding(horizontal = fieldInset), onFirst = {
                control.setPreamble(draftPreamble)
                draftPreamble = null
                notice = "已保存，下一轮生效"
            }, onSecond = {
                control.setPreamble(null)
                draftPreamble = null
                notice = "已恢复内置提示"
            })
        }

        // ---------------------------------------------------------------- 操作
        // 停止是危险方向，但不是"会丢东西"的破坏性操作：用文字提示方向即可，
        // 不弹确认——它随时可以再启动，代价是零。
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
                trailing = { EvText("重启", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Accent) },
            )
            EvDivider()
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
                trailing = { EvText("停止", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Danger) },
            )
        }

        status.lastError?.let { error ->
            EvSection(title = "最近一次失败") {
                EvText(error, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Danger)
            }
        }

        notice?.let { text ->
            EvNotice(text, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 行内的进程图标：运行时用强调色圆点，空闲用灰点——形状表达状态，和侧边栏一致。 */
@Composable
private fun uptime(startedAt: Long): String {
    val seconds = (System.currentTimeMillis() - startedAt) / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    return "${hours}h ${minutes % 60}m"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
