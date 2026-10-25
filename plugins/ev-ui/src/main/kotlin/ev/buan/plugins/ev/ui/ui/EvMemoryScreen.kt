package ev.buan.plugins.eta.ui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.StatusError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * What the agent remembers about one conversation, read from whichever plugin publishes a
 * [MemoryService].
 *
 * This is EV's memory, not Eta's: the panel shows the turns the agent brain stored for the active
 * session and can drop them. It is deliberately a view over the contract - enabling, configuring and
 * storing stay in the memory plugin's own `PluginStorage`.
 *
 * Clearing is the one write action, and it is two-step and scoped: only the agent's copy of this
 * session is dropped, while the chat page's own `sessions.json` bubbles are untouched, so the
 * transcript stays readable after clearing.
 */
@Composable
fun EtaMemoryScreen(
    sessionId: String,
    memory: () -> MemoryService?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var refreshToken by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<MemoryState>(MemoryState.Loading) }
    var confirmingClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // history() is a suspend call on another plugin: read it off the composition and re-read on every
    // refresh (or session change). A cancelled read leaves the previous state alone.
    LaunchedEffect(sessionId, refreshToken) {
        state = MemoryState.Loading
        confirmingClear = false
        state = readMemory(memory, sessionId)
    }

    EvPage(
        title = "记忆",
        modifier = modifier,
        onBack = onBack,
        actions = {
            // EvPage owns the clickable back arrow (ui-kit P3 fix); the page only adds refresh.
            Text(
                text = "刷新",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "刷新记忆") { refreshToken++ }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        when (val current = state) {
            MemoryState.Loading -> item { MemoryHint(title = null, detail = "读取中…") }

            MemoryState.Missing -> item {
                MemoryHint(
                    title = "agent-memory 未启用",
                    detail = "没有插件发布记忆服务。在「插件中心」启用 agent-memory 后回来刷新。",
                )
            }

            MemoryState.Empty -> item {
                MemoryHint(
                    title = "该会话没有记忆",
                    detail = "对话还没有写入记忆，或者这份记忆刚刚被清空。",
                )
            }

            is MemoryState.Failed -> item {
                MemoryHint(title = "读取记忆失败", detail = current.message)
            }

            is MemoryState.Loaded -> {
                item { EvSettingsSection("${current.messages.size} 条记忆") }
                item {
                    Text(
                        text = "会话 ${sessionId}",
                        style = MiuixTheme.textStyles.footnote1.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 8.dp),
                    )
                }
                item {
                    EvSettingsGroup {
                        current.messages.forEachIndexed { index, message ->
                            if (index > 0) EvSettingsDivider()
                            MemoryRow(message)
                        }
                    }
                }
                item {
                    if (confirmingClear) {
                        ConfirmClear(
                            onConfirm = {
                                confirmingClear = false
                                scope.launch { state = clearMemory(memory, sessionId) }
                            },
                            onCancel = { confirmingClear = false },
                        )
                    } else {
                        EvSettingsGroup {
                            EvSettingsRow(
                                title = "清空会话记忆",
                                glyph = EvGlyphKind.Trash,
                                tileColor = StatusError,
                                summary = CLEAR_NOTE,
                                onClick = { confirmingClear = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a read of [MemoryService.history] produced, including the two states that both look empty. */
internal sealed interface MemoryState {
    /** The suspend read is still running; shown instead of guessing between the states below. */
    data object Loading : MemoryState

    /** No plugin publishes a [MemoryService]: agent-memory is missing or disabled. */
    data object Missing : MemoryState

    /** A memory plugin answered, but this session has nothing stored. */
    data object Empty : MemoryState

    /** The stored turns, oldest first - the order [MemoryService.history] promises. */
    data class Loaded(val messages: List<AgentMessage>) : MemoryState

    /** The plugin threw; the message is shown instead of crashing the panel. */
    data class Failed(val message: String) : MemoryState
}

internal suspend fun readMemory(memory: () -> MemoryService?, sessionId: String): MemoryState {
    val service = try {
        memory()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        // A failing lookup is reported, not folded into "not installed": those are different fixes.
        return MemoryState.Failed(reason(error))
    } ?: return MemoryState.Missing
    val messages = try {
        service.history(sessionId)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        return MemoryState.Failed(reason(error))
    }
    return if (messages.isEmpty()) MemoryState.Empty else MemoryState.Loaded(messages)
}

/** Drops this session's memory, then re-reads it so the panel shows what is actually left. */
internal suspend fun clearMemory(memory: () -> MemoryService?, sessionId: String): MemoryState {
    val service = try {
        memory()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        return MemoryState.Failed(reason(error))
    } ?: return MemoryState.Missing
    try {
        service.clear(sessionId)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        return MemoryState.Failed("清空失败：${reason(error)}")
    }
    return readMemory(memory, sessionId)
}

internal fun AgentRole.label(): String = when (this) {
    AgentRole.SYSTEM -> "系统"
    AgentRole.USER -> "用户"
    AgentRole.ASSISTANT -> "助手"
    AgentRole.TOOL -> "工具"
}

/** One turn's row text: a tool result carries its tool name, everything else is just the content. */
internal fun memoryEntrySummary(message: AgentMessage): String {
    val content = message.content.ifBlank { "（空内容）" }
    val name = message.name?.takeIf { it.isNotBlank() }
    return if (message.role == AgentRole.TOOL && name != null) "工具 $name\n$content" else content
}

internal const val CLEAR_NOTE: String =
    "清空只影响 agent-memory 的这份记忆，不删除 eta-ui 的聊天历史。"

private fun reason(error: Throwable): String =
    error.message ?: error::class.simpleName ?: "未知错误"

private fun AgentRole.glyph(): EvGlyphKind = when (this) {
    AgentRole.SYSTEM -> EvGlyphKind.Lock
    AgentRole.USER -> EvGlyphKind.Person
    AgentRole.ASSISTANT -> EvGlyphKind.Sparkle
    AgentRole.TOOL -> EvGlyphKind.Gear
}

@Composable
private fun MemoryRow(message: AgentMessage) {
    EvSettingsRow(
        title = message.role.label(),
        glyph = message.role.glyph(),
        // The whole content is shown: the page scrolls, so a long turn is readable instead of clipped.
        summary = memoryEntrySummary(message),
        trailing = EvSettingsTrailing.None,
    )
}

@Composable
private fun ConfirmClear(onConfirm: () -> Unit, onCancel: () -> Unit) {
    EvSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "确认清空这份记忆？",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = CLEAR_NOTE,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    text = "取消",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clickable(onClickLabel = "取消清空", onClick = onCancel)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "确认清空",
                    style = MiuixTheme.textStyles.body1,
                    color = StatusError,
                    modifier = Modifier
                        .clickable(onClickLabel = "确认清空记忆", onClick = onConfirm)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryHint(title: String?, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
