package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import androidx.compose.foundation.layout.Box
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

    val scroll = rememberScrollState()
    // New output is what the person is waiting for, so the view follows it down automatically.
    LaunchedEffect(output.size, running) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EvThemeTokens.colors.surface0)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "返回", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(
                    EvGlyphKind.Chevron,
                    color = tokens.textPrimary,
                    // The chevron points forward; back is the same mark, mirrored.
                    modifier = Modifier.size(20.dp).graphicsLayer(rotationZ = 180f),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "终端",
                style = EvThemeTokens.type.title.style(tokens.textPrimary),
            )
        }
        Text(
            text = "直接执行命令。输出来自 run_shell，与 agent 用的是同一条通道。",
            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                .background(tokens.surface1)
                .verticalScroll(scroll)
                .padding(14.dp),
        ) {
            if (output.isEmpty()) {
                Text(
                    text = "输入命令后点执行。工作目录、权限与 agent 调用 run_shell 时一致。",
                    style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                )
            }
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

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
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
