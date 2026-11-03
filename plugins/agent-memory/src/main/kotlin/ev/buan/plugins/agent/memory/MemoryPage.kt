package ev.buan.plugins.agent.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * The memory plugin's own screen.
 *
 * This page exists to prove a layout rule rather than to be pretty: **the plugin that owns the data
 * renders the page that shows it**. ev-ui only routes here; it never imports [MemorySession] or the
 * storage layout, so a change to how memory is stored cannot break the chat surface.
 *
 * Two states, no navigation framework: the session list, and one open session. Back from the list
 * leaves the page; back from a session returns to the list.
 */
@Composable
internal fun MemoryPage(
    memory: StorageMemoryService,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    var sessions by remember { mutableStateOf<List<MemorySession>?>(null) }
    var opened by remember { mutableStateOf<MemorySession?>(null) }
    var messages by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { memory.sessions() }
    }
    // Reading a session re-runs whenever a different row is opened; the list itself stays mounted.
    LaunchedEffect(opened) {
        messages = opened?.let { session ->
            withContext(Dispatchers.IO) { memory.history(session.id) }
        }.orEmpty()
    }

    val current = opened
    if (current == null) {
        val list = sessions
        EvPage(
            title = "记忆",
            subtitle = list?.let { "${it.size} 段对话" } ?: "读取中",
            onBack = onBack,
            modifier = modifier,
        ) {
            when {
                list == null -> item {
                    Text(
                        text = "读取中…",
                        style = type.body.style(tokens.textTertiary),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }

                list.isEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            text = "还没有记忆",
                            style = type.title.style(tokens.textPrimary),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "和 EV 聊几句，这里就会出现按会话存下的记录。",
                            style = type.body.style(tokens.textSecondary),
                        )
                    }
                }

                else -> items(list, key = { it.id }) { session ->
                    SessionRow(session) { opened = session }
                }
            }
        }
    } else {
        EvPage(
            title = current.title,
            subtitle = "${messages.size} 条消息",
            onBack = { opened = null },
            modifier = modifier,
        ) {
            items(messages) { message -> MessageRow(message) }
        }
    }
}

/** One session: its first user line, how much is in it, when it last moved. */
@Composable
private fun SessionRow(session: MemorySession, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = session.title,
            style = type.body.style(tokens.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${session.count} 条 · ${stamp(session.lastAt)}",
            style = type.caption.style(tokens.textTertiary),
        )
    }
}

/** One stored message. Roles stay explicit: a stored turn is read later, out of context. */
@Composable
private fun MessageRow(message: AgentMessage) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    val label = when (message.role) {
        AgentRole.USER -> "你"
        AgentRole.ASSISTANT -> "EV"
        AgentRole.TOOL -> message.name ?: "工具"
        AgentRole.SYSTEM -> "系统"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = type.micro.style(tokens.textTertiary),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message.content,
            style = type.body.style(tokens.textPrimary),
        )
    }
}

private fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}
