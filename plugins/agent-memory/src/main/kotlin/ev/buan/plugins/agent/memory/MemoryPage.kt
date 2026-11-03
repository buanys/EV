package ev.buan.plugins.agent.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryNote
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
 * renders the page that shows it**. ev-ui only routes here; it never imports [MemorySession],
 * [MemoryNote] or the storage layout, so a change to how memory is stored cannot break the chat
 * surface.
 *
 * Sections, in the order the model uses them now:
 * 1. the notebook, pinned entries first - what carries across sessions;
 * 2. conversations - the raw history that answers "what did we say", not "what do we know".
 *
 * Back from the list leaves the page; back from an open session returns to the list.
 */
@Composable
internal fun MemoryPage(
    memory: StorageMemoryService,
    notes: NoteStore,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<MemorySession>?>(null) }
    var notebook by remember { mutableStateOf<List<MemoryNote>?>(null) }
    var opened by remember { mutableStateOf<MemorySession?>(null) }
    var messages by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }

    LaunchedEffect(Unit) {
        // One IO hop for both documents: they are read together and the page shows them together.
        withContext(Dispatchers.IO) {
            sessions = memory.sessions()
            notebook = notes.notes()
        }
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
        val entries = notebook
        val active = entries.orEmpty().filter { it.status == MemoryNote.STATUS_ACTIVE }
        val pinned = active.filter { it.pinned }
        val rest = active.filterNot { it.pinned }
        EvPage(
            title = "记忆",
            subtitle = when {
                list == null || entries == null -> "读取中"
                else -> "${active.size} 条记事 · ${list.size} 段对话"
            },
            onBack = onBack,
            modifier = modifier,
        ) {
            when {
                list == null || entries == null -> item {
                    Text(
                        text = "读取中…",
                        style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textTertiary),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }

                else -> {
                    item {
                        SectionHeader(
                            title = "记事本",
                            trailing = if (pinned.isEmpty()) {
                                if (active.isEmpty()) null else "${active.size} 条"
                            } else {
                                "${pinned.size} 条常驻"
                            },
                        )
                    }
                    if (active.isEmpty()) {
                        item {
                            Text(
                                text = "还没有记事本条目。值得跨对话留下的事，模型会自己写进来。",
                                style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textSecondary),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        items(pinned, key = { "pin_${it.id}" }) { note ->
                            NoteRow(note = note, pinned = true)
                        }
                        items(rest.take(MAX_NOTE_ROWS), key = { "note_${it.id}" }) { note ->
                            NoteRow(note = note, pinned = false)
                        }
                        if (rest.size > MAX_NOTE_ROWS) {
                            item {
                                Text(
                                    text = "另有 ${rest.size - MAX_NOTE_ROWS} 条未列出，用 recall 检索。",
                                    style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    item { SectionHeader(title = "对话", trailing = null) }
                    if (list.isEmpty()) {
                        item {
                            Text(
                                text = "还没有对话记录。聊几句，这里就会出现按会话存下的消息。",
                                style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textSecondary),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        items(list, key = { "sess_${it.id}" }) { session ->
                            SessionRow(session) { opened = session }
                        }
                    }
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

/** A section label: one line that separates the notebook from the transcripts below it. */
@Composable
private fun SectionHeader(title: String, trailing: String?) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = EvThemeTokens.type.title.style(tokens.textPrimary),
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(text = it, style = EvThemeTokens.type.caption.style(tokens.textTertiary))
        }
    }
}

/** One notebook entry: what it says, whether it rides along every turn, and when it last moved. */
@Composable
private fun NoteRow(note: MemoryNote, pinned: Boolean) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.title,
                style = type.body.style(tokens.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pinned) {
                Text(text = "常驻", style = type.caption.style(tokens.accent))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${kindLabel(note.kind)} · ${stamp(note.updatedAt)}",
            style = type.caption.style(tokens.textTertiary),
        )
        if (note.body.isNotBlank() && note.body != note.title) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note.body,
                style = type.body.style(tokens.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
        Text(text = label, style = type.micro.style(tokens.textTertiary))
        Spacer(Modifier.height(4.dp))
        Text(text = message.content, style = type.body.style(tokens.textPrimary))
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    MemoryNote.KIND_PREFERENCE -> "偏好"
    MemoryNote.KIND_FACT -> "事实"
    MemoryNote.KIND_TASK -> "任务"
    MemoryNote.KIND_CONVENTION -> "约定"
    else -> kind
}

private fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}

/** The notebook is browsable, not exhaustive: recall is the tool for "find the one I mean". */
private const val MAX_NOTE_ROWS = 12
