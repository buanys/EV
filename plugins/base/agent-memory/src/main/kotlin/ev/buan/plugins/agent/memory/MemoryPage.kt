package ev.buan.plugins.agent.memory

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.ui.EvCard
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var showPinned by remember { mutableStateOf(false) }
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

        showPinned -> PinnedPage(
            notes = notebook.orEmpty()
                .filter { it.pinned && it.status == MemoryNote.STATUS_ACTIVE },
            residentChars = Resident.render(notebook.orEmpty()).length,
            onOpen = { openedNote = it },
            onBack = { showPinned = false },
            modifier = modifier,
        )

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
                            onClick = { showPinned = true },
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

/** The notebook is browsable, not exhaustive: recall is the tool for "find the one I mean". */
private const val MAX_NOTE_ROWS = 12
