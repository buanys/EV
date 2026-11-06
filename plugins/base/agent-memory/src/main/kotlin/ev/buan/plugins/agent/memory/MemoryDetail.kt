package ev.buan.plugins.agent.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.ui.EvCard
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/** One notebook entry, opened: text, provenance, and the actions that change it. */
@Composable
internal fun NoteDetail(
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
internal fun MessageBlock(message: AgentMessage) {
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

private fun subtitleOf(note: MemoryNote): String = buildString {
    append(kindLabel(note.kind))
    if (note.origin == MemoryNote.ORIGIN_DISTILLED) append(" · 自动")
    if (note.pinned) append(" · 常驻")
    append(" · ").append(stamp(note.updatedAt))
}
