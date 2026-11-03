package ev.buan.plugins.agent.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch
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
 * Three states, no navigation framework:
 * 1. the list - notebook first (pinned on top), transcripts below;
 * 2. one notebook entry - full text, where it came from, and what can be done to it;
 * 3. one transcript - the raw messages.
 *
 * Editing is inline instead of a dialog: the note is the page, and a modal over a note you are
 * changing hides the context you are changing it from.
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
    var openedSession by remember { mutableStateOf<MemorySession?>(null) }
    var messages by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }

    var openedNote by remember { mutableStateOf<MemoryNote?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }
    var confirmArchive by remember { mutableStateOf(false) }

    /** Bumped after every write so the list and the open note re-read from the store. */
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            sessions = memory.sessions()
            notebook = notes.notes()
        }
    }
    // Reading a session re-runs whenever a different row is opened; the list itself stays mounted.
    LaunchedEffect(openedSession) {
        messages = openedSession?.let { session ->
            withContext(Dispatchers.IO) { memory.history(session.id) }
        }.orEmpty()
    }
    // Keep the open note in step with the reloaded list after save / pin / archive.
    LaunchedEffect(notebook, openedNote?.id) {
        val id = openedNote?.id ?: return@LaunchedEffect
        openedNote = notebook?.firstOrNull { it.id == id }
    }

    val note = openedNote
    val session = openedSession
    when {
        note != null -> NoteDetail(
            note = note,
            editing = editing,
            draftTitle = draftTitle,
            draftBody = draftBody,
            confirmArchive = confirmArchive,
            onDraftTitle = { draftTitle = it },
            onDraftBody = { draftBody = it },
            onBack = {
                if (editing) {
                    editing = false
                } else {
                    openedNote = null
                    confirmArchive = false
                }
            },
            onEdit = {
                draftTitle = note.title
                draftBody = note.body
                editing = true
                confirmArchive = false
            },
            onCancelEdit = { editing = false },
            onSave = {
                val title = draftTitle.trim()
                val body = draftBody.trim()
                if (title.isNotEmpty() && body.isNotEmpty()) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            notes.update(note.id, title = title, body = body)
                        }
                        editing = false
                        revision++
                    }
                }
            },
            onTogglePin = {
                scope.launch {
                    withContext(Dispatchers.IO) { notes.pin(note.id, !note.pinned) }
                    revision++
                }
            },
            onArchive = {
                if (!confirmArchive) {
                    confirmArchive = true
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { notes.archive(note.id) }
                        confirmArchive = false
                        openedNote = null
                        revision++
                    }
                }
            },
            onCancelArchive = { confirmArchive = false },
            modifier = modifier,
        )

        session != null -> EvPage(
            title = session.title,
            subtitle = "${messages.size} 条消息",
            onBack = { openedSession = null },
            modifier = modifier,
        ) {
            items(messages) { message -> MessageRow(message) }
        }

        else -> {
            val active = notebook.orEmpty().filter { it.status == MemoryNote.STATUS_ACTIVE }
            val pinned = active.filter { it.pinned }
            val rest = active.filterNot { it.pinned }
            EvPage(
                title = "记忆",
                subtitle = when {
                    sessions == null || notebook == null -> "读取中"
                    else -> "${active.size} 条记事 · ${sessions.orEmpty().size} 段对话"
                },
                onBack = onBack,
                modifier = modifier,
            ) {
                if (sessions == null || notebook == null) {
                    item { LoadingRow() }
                } else {
                    item {
                        SectionHeader(
                            title = "记事本",
                            trailing = when {
                                active.isEmpty() -> null
                                pinned.isEmpty() -> "${active.size} 条"
                                else -> "${pinned.size} 条常驻"
                            },
                        )
                    }
                    if (active.isEmpty()) {
                        item {
                            HintRow("还没有记事本条目。值得跨对话留下的事，模型会自己写进来。")
                        }
                    } else {
                        items(pinned, key = { "pin_${it.id}" }) { item ->
                            NoteRow(note = item, pinned = true) { openedNote = item }
                        }
                        items(rest.take(MAX_NOTE_ROWS), key = { "note_${it.id}" }) { item ->
                            NoteRow(note = item, pinned = false) { openedNote = item }
                        }
                        if (rest.size > MAX_NOTE_ROWS) {
                            item {
                                HintRow("另有 ${rest.size - MAX_NOTE_ROWS} 条未列出，用 recall 检索。")
                            }
                        }
                    }

                    item { SectionHeader(title = "对话", trailing = null) }
                    val list = sessions.orEmpty()
                    if (list.isEmpty()) {
                        item { HintRow("还没有对话记录。聊几句，这里就会出现按会话存下的消息。") }
                    } else {
                        items(list, key = { "sess_${it.id}" }) { item ->
                            SessionRow(item) { openedSession = item }
                        }
                    }
                }
            }
        }
    }
}

/** One notebook entry, with everything that can be done to it. */
@Composable
private fun NoteDetail(
    note: MemoryNote,
    editing: Boolean,
    draftTitle: String,
    draftBody: String,
    confirmArchive: Boolean,
    onDraftTitle: (String) -> Unit,
    onDraftBody: (String) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onCancelArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    EvPage(
        title = if (editing) "编辑条目" else note.title,
        subtitle = subtitleOf(note),
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (!editing) {
                TextAction("编辑", tokens.accent, onEdit)
            }
        },
    ) {
        if (editing) {
            item { FieldLabel("标题") }
            item { NoteField(draftTitle, onDraftTitle, minLines = 1) }
            item { FieldLabel("正文") }
            item { NoteField(draftBody, onDraftBody, minLines = 4) }
            item {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    TextAction("保存", tokens.accent, onSave)
                    TextAction("取消", tokens.textTertiary, onCancelEdit)
                }
            }
        } else {
            item {
                Text(
                    text = note.body.ifBlank { "（没有正文）" },
                    style = type.body.style(tokens.textPrimary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            item {
                Text(
                    text = provenanceOf(note),
                    style = type.caption.style(tokens.textTertiary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextAction(if (note.pinned) "移出常驻" else "设为常驻", tokens.textSecondary, onTogglePin)
                    TextAction("归档", tokens.danger, onArchive)
                }
            }
            if (confirmArchive) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "归档后不再出现在列表里，来源仍保留。再点一次「归档」确认。",
                            style = type.caption.style(tokens.warning),
                            modifier = Modifier.weight(1f),
                        )
                        TextAction("取消", tokens.textTertiary, onCancelArchive)
                    }
                }
            }
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
private fun NoteRow(note: MemoryNote, pinned: Boolean, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
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
            text = buildString {
                append(kindLabel(note.kind))
                if (note.origin == MemoryNote.ORIGIN_DISTILLED) append(" · 自动")
                append(" · ").append(stamp(note.updatedAt))
            },
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

@Composable
private fun LoadingRow() {
    Text(
        text = "读取中…",
        style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textTertiary),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textSecondary),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** The one editable control on this page: no material3 field, no floating label, no outline. */
@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, minLines: Int) {
    val tokens = EvThemeTokens.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        minLines = minLines,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .background(tokens.surface2, EvThemeTokens.radii.smallShape)
            .padding(12.dp),
    )
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = EvThemeTokens.type.label.style(color),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    MemoryNote.KIND_PREFERENCE -> "偏好"
    MemoryNote.KIND_FACT -> "事实"
    MemoryNote.KIND_TASK -> "任务"
    MemoryNote.KIND_CONVENTION -> "约定"
    else -> kind
}

private fun subtitleOf(note: MemoryNote): String = buildString {
    append(kindLabel(note.kind))
    if (note.origin == MemoryNote.ORIGIN_DISTILLED) append(" · 自动")
    if (note.pinned) append(" · 常驻")
    append(" · ").append(stamp(note.updatedAt))
}

private fun provenanceOf(note: MemoryNote): String = buildString {
    append("创建于 ").append(stamp(note.createdAt))
    if (note.sources.isNotEmpty()) {
        append(" · 依据 ").append(note.sources.size).append(" 条对话")
    }
    note.supersedes?.let { append(" · 取代了 ").append(it) }
}

private fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}

/** The notebook is browsable, not exhaustive: recall is the tool for "find the one I mean". */
private const val MAX_NOTE_ROWS = 12
