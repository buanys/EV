package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.plugins.ev.ui.EvCharacter
import ev.buan.plugins.ev.ui.newCharacter
import ev.buan.ui.EvEmptyState
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.StatusError
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The local character library: pick who the current conversation talks to, or write a new persona.
 *
 * A character is nothing but a system prompt plus the text that describes it. Selecting one stores
 * its id on the *session* (not on the app), so two conversations can use two different characters,
 * and the prompt reaches the model only as `AgentService.send(systemPrompt = ...)` - it never becomes
 * a chat bubble and never enters agent memory.
 *
 * The screen owns only editing state; library changes go through [onSave] / [onDelete] / [onSelect]
 * so the plugin can write `characters.json` and `sessions.json` in one place.
 *
 * Internal because [EvCharacter] is a plugin-internal model: this page is not part of any contract.
 */
@Composable
internal fun EvCharactersScreen(
    characters: List<EvCharacter>,
    selectedId: String?,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
    onSave: (EvCharacter) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EvCharacter?>(null) }

    val current = editing
    if (current != null) {
        CharacterEditor(
            character = current,
            isNew = characters.none { it.id == current.id },
            onCancel = { editing = null },
            onSave = { saved ->
                onSave(saved)
                editing = null
            },
            onDelete = {
                onDelete(current.id)
                editing = null
            },
        )
        return
    }

    EvPage(
        title = "角色",
        subtitle = "会话的角色设定",
        modifier = modifier,
        onBack = onBack,
        actions = {
            // EvPage owns the clickable back arrow; the page only adds its primary action.
            Text(
                text = "新建",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "新建角色") { editing = newCharacter() }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        if (characters.isEmpty()) {
            item {
                EvEmptyState(
                    title = "还没有角色",
                    detail = "点右上角「新建」起个名字，再写一段设定。角色只影响模型收到的指令，不写入记忆。",
                    glyph = EvGlyphKind.Person,
                )
            }
        } else {
            item { EvSettingsSection("${characters.size} 个角色 · 开关表示当前会话是否使用") }
            item {
                EvSettingsGroup {
                    characters.forEachIndexed { index, character ->
                        if (index > 0) EvSettingsDivider()
                        CharacterRow(
                            character = character,
                            selected = character.id == selectedId,
                            onEdit = { editing = character },
                            onSelectedChange = { checked -> onSelect(if (checked) character.id else null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterRow(
    character: EvCharacter,
    selected: Boolean,
    onEdit: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
) {
    val description = descriptionSummary(character.description)
    EvSettingsRow(
        title = character.name,
        glyph = EvGlyphKind.Person,
        summary = if (selected) "当前会话使用中 · ${description ?: "（无描述）"}" else description,
        trailing = EvSettingsTrailing.Switch,
        checked = selected,
        onCheckedChange = onSelectedChange,
        onClick = onEdit,
    )
}

/** The editor: three fields, one save rule, and a two-step delete for an existing character. */
@Composable
private fun CharacterEditor(
    character: EvCharacter,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (EvCharacter) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(character.id) { mutableStateOf(character.name) }
    var description by remember(character.id) { mutableStateOf(character.description) }
    var systemPrompt by remember(character.id) { mutableStateOf(character.systemPrompt) }
    var confirmingDelete by remember(character.id) { mutableStateOf(false) }

    // Recomputed every frame; only the saved copy is written to the library.
    val draft = character.copy(
        name = name.trim(),
        description = description.trim(),
        systemPrompt = systemPrompt.trim(),
        updatedAt = System.currentTimeMillis(),
    )
    val canSave = draft.isSaveable

    EvPage(
        title = if (isNew) "新建角色" else "编辑角色",
        onBack = onCancel,
        actions = {
            Text(
                text = "保存",
                style = MiuixTheme.textStyles.body1,
                color = if (canSave) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier
                    .clickable(enabled = canSave, onClickLabel = "保存角色") { onSave(draft) }
                    .padding(horizontal = 12.dp),
            )
            Text(
                text = "取消",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clickable(onClickLabel = "放弃编辑", onClick = onCancel)
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        item { LibraryFieldLabel("名称") }
        item {
            LibraryTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "例如：严谨的审阅者",
                singleLine = true,
            )
        }
        item { LibraryFieldLabel("描述") }
        item {
            LibraryTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "一句话说明这个角色做什么（可留空）",
            )
        }
        item { LibraryFieldLabel("角色设定") }
        item {
            LibraryTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                placeholder = "写给模型的指令，例如：你是严格的代码审阅者，先指出风险再给结论。",
                minHeight = 160.dp,
            )
        }
        item {
            LibraryHint(
                title = null,
                detail = "角色设定会作为系统提示注入这个会话的每一轮对话；它不会写入记忆，也不会出现在聊天气泡里。",
            )
        }
        if (!canSave) {
            item {
                LibraryHint(
                    title = null,
                    detail = "名称与角色设定都不能为空，填写后才能保存。",
                    tint = StatusError,
                )
            }
        }
        if (!isNew) {
            item {
                if (confirmingDelete) {
                    LibraryDeleteConfirm(
                        title = "确认删除「${draft.name.ifBlank { character.name }}」？",
                        detail = "使用它的会话会恢复为无角色；已经写入的记忆与聊天记录不受影响。",
                        onConfirm = onDelete,
                        onCancel = { confirmingDelete = false },
                    )
                } else {
                    LibraryDeleteRow(
                        title = "删除角色",
                        summary = "使用它的会话会恢复为无角色",
                        onRequest = { confirmingDelete = true },
                    )
                }
            }
        }
    }
}
