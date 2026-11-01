package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

/**
 * A shell, without a conversation.
 *
 * It calls the `run_shell` tool the shell plugin publishes, through the same [ToolRegistry] the
 * model uses - so it is the same process, the same privileges and the same output the agent would
 * get. It deliberately bypasses the approval gate: the gate exists because a *model* can be talked
 * into a dangerous command, while this screen is a human typing one. If the tool is not published
 * the page says so instead of pretending the command ran.
 */
@Composable
fun EvTerminalScreen(
    registry: () -> ToolRegistry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val output = remember { mutableStateListOf<String>() }

    fun run() {
        val body = command.trim()
        if (body.isEmpty() || running) return
        val tool = registry()?.lookup("run_shell")
        if (tool == null) {
            output += "$ $body\n（shell 工具未发布：启用 agent-shell 插件后再试）"
            command = ""
            return
        }
        running = true
        output += "$ $body"
        command = ""
        scope.launch {
            val result = runCatching {
                tool.invoke(JSONObject().put("command", body).put("timeout_seconds", 30).toString())
            }.getOrElse { error -> "调用失败：${error.message ?: error::class.simpleName}" }
            output += renderShellResult(result)
            running = false
        }
    }

    EvPage(title = "终端", modifier = modifier, onBack = onBack) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    text = "直接执行命令。输出来自 run_shell，与 agent 用的是同一条通道。",
                    style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                )
            }
        }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                    .background(tokens.surface1)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                output.forEach { block ->
                    Text(
                        text = block,
                        style = TextStyle(
                            color = tokens.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (running) {
                    Text(
                        text = "运行中…",
                        style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = tokens.textPrimary,
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(tokens.surface2)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(if (running) tokens.surface2 else tokens.accent)
                        .clickable(onClickLabel = "执行", onClick = { run() })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (running) "…" else "执行",
                        style = EvThemeTokens.type.label.style(if (running) tokens.textTertiary else tokens.onAccent),
                    )
                }
            }
        }
    }
}

/**
 * Shell output as text. The tool answers with a JSON object - exit code, timeout flag and the two
 * streams - so this flattens it instead of showing raw JSON with escaped newlines.
 */
private fun renderShellResult(raw: String): String {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    val stdout = json.optString("stdout")
    val stderr = json.optString("stderr")
    val exit = json.opt("exit")
    val timedOut = json.optBoolean("timedOut")
    val shell = json.optString("shell")
    val builder = StringBuilder()
    if (stdout.isNotBlank()) builder.append(stdout.trimEnd())
    if (stderr.isNotBlank()) {
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(stderr.trimEnd())
    }
    if (builder.isEmpty()) builder.append("（无输出）")
    builder.append("\n\n[exit=").append(exit)
    if (timedOut) builder.append(" · 超时")
    if (shell.isNotBlank()) builder.append(" · ").append(shell)
    builder.append(']')
    return builder.toString()
}
