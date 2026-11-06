package ev.buan.plugins.agent.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.ui.EvCard
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * The hero: how many notes ride in every prompt, how much of the budget that uses, and how far the
 * automatic distiller has got. One card answers the only three questions this page can be opened
 * for at a glance.
 */
@Composable
internal fun MemoryHero(
    pinnedCount: Int,
    residentChars: Int,
    totalMessages: Int,
    status: DistillStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    val budget = Resident.BUDGET_CHARS
    val fraction = (residentChars.toFloat() / budget).coerceIn(0f, 1f)
    EvCard(
        modifier = modifier,
        insideMargin = PaddingValues(20.dp),
        onClick = onClick,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = distillLine(totalMessages, status),
                style = type.caption.style(tokens.textTertiary),
                modifier = Modifier.weight(1f),
            )
            // The card is a door, not a dashboard: the chevron says "there is a list behind this".
            EvGlyph(
                kind = EvGlyphKind.Chevron,
                color = tokens.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
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

/** One notebook entry: kind stripe, title, one line of meta, two lines of body. */
@Composable
internal fun NoteRow(note: MemoryNote, onClick: () -> Unit) {
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
internal fun SessionRow(session: MemorySession, onClick: () -> Unit) {
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

/** The list behind the hero card: every note that rides in the prompt, and the room it takes. */
@Composable
internal fun PinnedPage(
    notes: List<MemoryNote>,
    residentChars: Int,
    onOpen: (MemoryNote) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    EvPage(
        title = "常驻记忆",
        subtitle = if (notes.isEmpty()) {
            "0 条"
        } else {
            "${notes.size} 条 · $residentChars / ${Resident.BUDGET_CHARS} 字"
        },
        onBack = onBack,
        modifier = modifier,
    ) {
        if (notes.isEmpty()) {
            item {
                EmptyCard(
                    icon = EvGlyphKind.Memory,
                    title = "没有常驻条目",
                    body = "在条目详情里打开「常驻上下文」，它就会每一轮都出现在提示词里。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            item {
                EvCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    notes.forEachIndexed { index, note ->
                        NoteRow(note) { onOpen(note) }
                        if (index != notes.lastIndex) Hairline(startIndent = 32.dp)
                    }
                }
            }
            item {
                Text(
                    text = "这 ${notes.size} 条每轮都会进入提示词，共用 ${Resident.BUDGET_CHARS} 字预算。点一条可以改正文或移出常驻。",
                    style = type.caption.style(tokens.textTertiary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** The three layers, explained once at the bottom of a short page. */
@Composable
internal fun MemoryLegend(modifier: Modifier = Modifier) {
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
private fun kindColor(kind: String): Color = when (kind) {
    MemoryNote.KIND_PREFERENCE -> EvThemeTokens.colors.accent
    MemoryNote.KIND_TASK -> EvThemeTokens.colors.warning
    MemoryNote.KIND_CONVENTION -> EvThemeTokens.colors.success
    else -> EvThemeTokens.colors.textTertiary
}

/** Mirrors `Distiller.MIN_MESSAGES`; the page only explains the rule, it does not enforce it. */
private const val MIN_DISTILL_MESSAGES = 20
