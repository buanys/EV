package ev.buan.plugins.agent.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.ui.EvCard
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * The memory plugin's own screen.
 *
 * The layout answers three questions in the order a person asks them: *how much do you remember*
 * (the hero card), *what exactly* (the notebook card), and *where did it come from* (the
 * transcripts below). Cards carry the sections because the page has three kinds of content and a
 * flat list would make them look identical; the surface step does the grouping that dividers alone
 * cannot.
 *
 * Three states, no navigation framework:
 * 1. the list - hero, notebook, transcripts;
 * 2. one notebook entry - full text, provenance, and what can be done to it;
 * 3. one transcript - the raw messages.
 */
@Composable
internal fun MemoryPage(
    memory: StorageMemoryService,
    notes: NoteStore,
    distillStatus: (() -> DistillStatus?)? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<MemorySession>?>(null) }
    var notebook by remember { mutableStateOf<List<MemoryNote>?>(null) }
    var distillation by remember { mutableStateOf<DistillStatus?>(null) }
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
        distillation = runCatching { distillStatus?.invoke() }.getOrNull()
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
            items(messages) { message -> MessageBlock(message) }
        }

        else -> {
            val active = notebook.orEmpty().filter { it.status == MemoryNote.STATUS_ACTIVE }
            val pinned = active.filter { it.pinned }
            val rest = active.filterNot { it.pinned }
            val list = sessions.orEmpty()
            val pinnedChars = Resident.render(notebook.orEmpty()).length
            EvPage(
                title = "记忆",
                subtitle = when {
                    sessions == null || notebook == null -> "读取中"
                    else -> "模型跨会话记住的东西"
                },
                onBack = onBack,
                modifier = modifier,
            ) {
                if (sessions == null || notebook == null) {
                    item { LoadingRow() }
                } else {
                    item {
                        MemoryHero(
                            pinnedCount = pinned.size,
                            residentChars = pinnedChars,
                            totalMessages = list.sumOf { it.count },
                            status = distillation,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    item { SectionLabel("记事本", "${active.size} 条") }
                    if (active.isEmpty()) {
                        item {
                            EmptyCard(
                                icon = EvGlyphKind.Memory,
                                title = "还没有记事",
                                body = "值得跨对话留下的事，模型会自己写进来；也可以直接对它说「记住…」。",
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        item {
                            EvCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                val visible = pinned + rest.take(MAX_NOTE_ROWS)
                                visible.forEachIndexed { index, item ->
                                    NoteRow(item) { openedNote = item }
                                    if (index != visible.lastIndex) {
                                        Hairline(startIndent = 32.dp)
                                    }
                                }
                            }
                        }
                        if (rest.size > MAX_NOTE_ROWS) {
                            item {
                                Text(
                                    text = "另有 ${rest.size - MAX_NOTE_ROWS} 条未展开，用 recall 检索。",
                                    style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                                )
                            }
                        }
                    }

                    item { SectionLabel("对话", "${list.size} 段") }
                    if (list.isEmpty()) {
                        item {
                            EmptyCard(
                                icon = EvGlyphKind.Chat,
                                title = "还没有对话",
                                body = "聊几句，这里就会出现按会话存下的完整消息。",
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        item {
                            EvCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                list.forEachIndexed { index, item ->
                                    SessionRow(item) { openedSession = item }
                                    if (index != list.lastIndex) Hairline(startIndent = 20.dp)
                                }
                            }
                        }
                    }

                    // Fills the bottom of a short page with an explanation instead of a void: what
                    // the three layers mean is exactly what a new user is looking for here.
                    item {
                        MemoryLegend(Modifier.padding(horizontal = 20.dp, vertical = 30.dp))
                    }
                }
            }
        }
    }
}

/**
 * The hero: how many notes ride in every prompt, how much of the budget that uses, and how far the
 * automatic distiller has got. One card answers the only three questions this page can be opened
 * for at a glance.
 */
@Composable
private fun MemoryHero(
    pinnedCount: Int,
    residentChars: Int,
    totalMessages: Int,
    status: DistillStatus?,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    val budget = Resident.BUDGET_CHARS
    val fraction = (residentChars.toFloat() / budget).coerceIn(0f, 1f)
    EvCard(
        modifier = modifier,
        insideMargin = PaddingValues(20.dp),
        // One step above the list cards: the hero is the only block that answers "how much do you
        // remember", so it gets its own surface level instead of another identical card.
        colors = CardDefaults.defaultColors(
            color = tokens.surface2,
            contentColor = tokens.textPrimary,
        ),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "$pinnedCount",
                    style = type.display.style(tokens.textPrimary).copy(fontSize = 36.sp),
                )
                Text(
                    text = "条常驻",
                    style = type.caption.style(tokens.textTertiary),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // Characters are the honest unit here: 2% of a 3000-character budget is a number
                // nobody can act on, "63 / 3000 字" says exactly how full the window is.
                Text(
                    text = "$residentChars / $budget",
                    style = type.title.style(tokens.textPrimary),
                )
                Text(
                    text = "字上下文预算",
                    style = type.caption.style(tokens.textTertiary),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        BudgetBar(fraction)
        Spacer(Modifier.height(12.dp))
        Text(
            text = distillLine(totalMessages, status),
            style = type.caption.style(tokens.textTertiary),
        )
    }
}

@Composable
private fun BudgetBar(fraction: Float) {
    val tokens = EvThemeTokens.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(tokens.surface3, EvThemeTokens.radii.fullShape),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.015f))
                    .height(4.dp)
                    .background(tokens.accent, EvThemeTokens.radii.fullShape),
            )
        }
    }
}

private fun distillLine(totalMessages: Int, status: DistillStatus?): String {
    if (totalMessages == 0) return "还没有对话内容可整理"
    if (status == null) return "读取整理进度…"
    if (status.running) return "正在整理对话…"
    val pending = (totalMessages - status.covered).coerceAtLeast(0)
    return when {
        pending == 0 -> "已整理 ${status.covered} 条消息 · 暂无待整理"
        pending >= MIN_DISTILL_MESSAGES -> "已记录 $totalMessages 条 · 空闲 15 分钟后整理"
        else -> "已记录 $totalMessages 条 · 还差 ${MIN_DISTILL_MESSAGES - pending} 条触发整理"
    }
}

/** A section label: quiet, numbered, and never competing with the card it introduces. */
@Composable
private fun SectionLabel(title: String, trailing: String) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = type.caption.style(tokens.textSecondary).copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
        )
        Text(text = trailing, style = type.caption.style(tokens.textTertiary))
    }
}

/** One notebook entry: kind stripe, title, one line of meta, two lines of body. */
@Composable
private fun NoteRow(note: MemoryNote, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(3.dp)
                .height(34.dp)
                .background(kindColor(note.kind), EvThemeTokens.radii.fullShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = type.body.style(tokens.textPrimary).copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (note.pinned) {
                    Spacer(Modifier.width(8.dp))
                    PinBadge()
                }
            }
            Spacer(Modifier.height(3.dp))
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
                    style = type.caption.style(tokens.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        EvGlyph(
            kind = EvGlyphKind.Chevron,
            color = tokens.textTertiary,
            modifier = Modifier.size(16.dp).padding(top = 4.dp),
        )
    }
}

/** The "rides in every prompt" mark: a pill, not a second colour on the title. */
@Composable
private fun PinBadge() {
    val tokens = EvThemeTokens.colors
    Box(
        Modifier
            .background(tokens.accentSoft, EvThemeTokens.radii.fullShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "常驻",
            style = EvThemeTokens.type.micro.style(tokens.accent),
        )
    }
}

/** One session: its first user line, how many messages, when it last moved. */
@Composable
private fun SessionRow(session: MemorySession, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = type.body.style(tokens.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${session.count} 条 · ${stamp(session.lastAt)}",
                style = type.caption.style(tokens.textTertiary),
            )
        }
        EvGlyph(
            kind = EvGlyphKind.Chevron,
            color = tokens.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** One notebook entry, opened: text, provenance, and the actions that change it. */
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
                Spacer(Modifier.width(6.dp))
                TextAction("编辑", tokens.accent, onEdit)
            }
        },
    ) {
        if (editing) {
            item { SectionLabel("标题", "${draftTitle.length} / 40") }
            item {
                EvCard(Modifier.padding(horizontal = 16.dp), insideMargin = PaddingValues(14.dp)) {
                    NoteField(draftTitle, onDraftTitle, minLines = 1)
                }
            }
            item { SectionLabel("正文", "${draftBody.length} 字") }
            item {
                EvCard(Modifier.padding(horizontal = 16.dp), insideMargin = PaddingValues(14.dp)) {
                    NoteField(draftBody, onDraftBody, minLines = 5)
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextAction("取消", tokens.textTertiary, onCancelEdit)
                    TextAction("保存", tokens.accent, onSave)
                }
            }
        } else {
            item {
                EvCard(Modifier.padding(horizontal = 16.dp), insideMargin = PaddingValues(16.dp)) {
                    Text(
                        text = note.body.ifBlank { "（没有正文）" },
                        style = type.body.style(tokens.textPrimary),
                    )
                }
            }
            item { SectionLabel("详情", "") }
            item {
                EvCard(Modifier.padding(horizontal = 16.dp), insideMargin = PaddingValues(0.dp)) {
                    MetaRow("类型", kindLabel(note.kind))
                    Hairline(startIndent = 16.dp)
                    MetaRow("创建", stamp(note.createdAt))
                    Hairline(startIndent = 16.dp)
                    MetaRow(
                        "依据",
                        if (note.sources.isEmpty()) "无（模型手写）" else "${note.sources.size} 条对话",
                    )
                    note.supersedes?.let {
                        Hairline(startIndent = 16.dp)
                        MetaRow("取代", it)
                    }
                }
            }
            item { SectionLabel("操作", "") }
            item {
                EvCard(Modifier.padding(horizontal = 16.dp), insideMargin = PaddingValues(0.dp)) {
                    ActionRow(
                        icon = EvGlyphKind.Memory,
                        label = "常驻上下文",
                        value = if (note.pinned) "已开启" else "已关闭",
                        valueColor = if (note.pinned) tokens.accent else tokens.textTertiary,
                        onClick = onTogglePin,
                    )
                    Hairline(startIndent = 16.dp)
                    ActionRow(
                        icon = EvGlyphKind.Trash,
                        label = "归档",
                        value = if (confirmArchive) "再点一次确认" else "",
                        valueColor = tokens.warning,
                        labelColor = tokens.danger,
                        onClick = onArchive,
                    )
                }
            }
            if (confirmArchive) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "归档后不再出现在列表里，来源仍保留。",
                            style = type.caption.style(tokens.textTertiary),
                            modifier = Modifier.weight(1f),
                        )
                        TextAction("取消", tokens.textTertiary, onCancelArchive)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = type.caption.style(tokens.textTertiary),
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = type.caption.style(tokens.textSecondary),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(
    icon: EvGlyphKind,
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color = EvThemeTokens.colors.textPrimary,
    onClick: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(kind = icon, color = tokens.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = type.body.style(labelColor),
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(text = value, style = type.caption.style(valueColor))
        }
    }
}

/** One stored message, in a card so a long transcript keeps the page's shape. */
@Composable
private fun MessageBlock(message: AgentMessage) {
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
private fun EmptyCard(
    icon: EvGlyphKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    EvCard(modifier = modifier, insideMargin = PaddingValues(24.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EvGlyph(kind = icon, color = tokens.textTertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(text = title, style = type.label.style(tokens.textSecondary))
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = type.caption.style(tokens.textTertiary),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The three layers, explained once at the bottom of a short page. */
@Composable
private fun MemoryLegend(modifier: Modifier = Modifier) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "记忆这样工作",
            style = type.micro.style(tokens.textTertiary).copy(fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(10.dp))
        LegendRow("常驻", "每轮都带在提示词里，共用 ${Resident.BUDGET_CHARS} 字预算")
        LegendRow("记事本", "跨会话保存，需要时用 recall 检索")
        LegendRow("对话", "原始消息，按会话完整保留")
    }
}

@Composable
private fun LegendRow(label: String, detail: String) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = type.caption.style(tokens.textSecondary),
            modifier = Modifier.width(56.dp),
        )
        Text(text = detail, style = type.caption.style(tokens.textTertiary))
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

/** The hairline inside a card: indented, ghost weight, never full-bleed. */
@Composable
private fun Hairline(startIndent: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(EvThemeTokens.colors.dividerGhost),
    )
}

@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, minLines: Int) {
    val tokens = EvThemeTokens.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
        cursorBrush = SolidColor(tokens.accent),
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = EvThemeTokens.type.label.style(color),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun kindColor(kind: String): Color = when (kind) {
    MemoryNote.KIND_PREFERENCE -> EvThemeTokens.colors.accent
    MemoryNote.KIND_TASK -> EvThemeTokens.colors.warning
    MemoryNote.KIND_CONVENTION -> EvThemeTokens.colors.success
    else -> EvThemeTokens.colors.textTertiary
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

private fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val target = Date(millis)
    val today = day.format(Date())
    if (day.format(target) == today) return "今天 ${clock.format(target)}"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (day.format(target) == day.format(yesterday.time)) return "昨天 ${clock.format(target)}"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(target)
}

/** The notebook is browsable, not exhaustive: recall is the tool for "find the one I mean". */
private const val MAX_NOTE_ROWS = 12

/** Mirrors `Distiller.MIN_MESSAGES`; the page only explains the rule, it does not enforce it. */
private const val MIN_DISTILL_MESSAGES = 20
