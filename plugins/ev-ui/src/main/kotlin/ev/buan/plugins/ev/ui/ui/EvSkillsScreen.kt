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
import ev.buan.plugins.ev.ui.EvSkill
import ev.buan.plugins.ev.ui.newSkill
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
 * The local skills library: text instructions a conversation can switch on.
 *
 * A skill is not code and this plugin has no runtime for one: selecting a skill stores its id on the
 * *session*, and the next turn sends `instruction` to the model inside the system prompt (under the
 * `## Skill` heading). Nothing is executed, nothing is written into the chat bubbles, and the brain
 * never stores the prompt, so a skill cannot leak into agent memory.
 *
 * The screen owns only editing state; library changes go through [onSave] / [onDelete] / [onSelect]
 * so the plugin can write `skills.json` and `sessions.json` in one place.
 */
@Composable
internal fun EvSkillsScreen(
    skills: List<EvSkill>,
    selectedId: String?,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
    onSave: (EvSkill) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EvSkill?>(null) }

    val current = editing
    if (current != null) {
        SkillEditor(
            skill = current,
            isNew = skills.none { it.id == current.id },
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
        title = "Skills",
        subtitle = "会话的文本指令",
        modifier = modifier,
        onBack = onBack,
        actions = {
            // EvPage owns the clickable back arrow; the page only adds its primary action.
            Text(
                text = "新建",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "新建 Skill") { editing = newSkill() }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        if (skills.isEmpty()) {
            item {
                EvEmptyState(
                    title = "还没有 Skill",
                    detail = "点右上角「新建」，写下希望模型在这一轮遵守的做法。Skill 只是文本指令，不执行代码。",
                    glyph = EvGlyphKind.Skill,
                )
            }
        } else {
            item { EvSettingsSection("${skills.size} 个 Skill · 开关表示当前会话是否使用") }
            item {
                EvSettingsGroup {
                    skills.forEachIndexed { index, skill ->
                        if (index > 0) EvSettingsDivider()
                        SkillRow(
                            skill = skill,
                            selected = skill.id == selectedId,
                            onEdit = { editing = skill },
                            onSelectedChange = { checked -> onSelect(if (checked) skill.id else null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: EvSkill,
    selected: Boolean,
    onEdit: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
) {
    val description = descriptionSummary(skill.description)
    EvSettingsRow(
        title = skill.name,
        glyph = EvGlyphKind.Sparkle,
        summary = if (selected) "当前会话使用中 · ${description ?: "（无描述）"}" else description,
        trailing = EvSettingsTrailing.Switch,
        checked = selected,
        onCheckedChange = onSelectedChange,
        onClick = onEdit,
    )
}

/** The editor: three fields, one save rule, and a two-step delete for an existing skill. */
@Composable
private fun SkillEditor(
    skill: EvSkill,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (EvSkill) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var description by remember(skill.id) { mutableStateOf(skill.description) }
    var instruction by remember(skill.id) { mutableStateOf(skill.instruction) }
    var confirmingDelete by remember(skill.id) { mutableStateOf(false) }

    // Recomputed every frame; only the saved copy is written to the library.
    val draft = skill.copy(
        name = name.trim(),
        description = description.trim(),
        instruction = instruction.trim(),
        updatedAt = System.currentTimeMillis(),
    )
    val canSave = draft.isSaveable

    EvPage(
        title = if (isNew) "新建 Skill" else "编辑 Skill",
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
                    .clickable(enabled = canSave, onClickLabel = "保存 Skill") { onSave(draft) }
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
                placeholder = "例如：先列风险再给结论",
                singleLine = true,
            )
        }
        item { LibraryFieldLabel("描述") }
        item {
            LibraryTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "一句话说明这个 Skill 做什么（可留空）",
            )
        }
        item { LibraryFieldLabel("指令") }
        item {
            LibraryTextField(
                value = instruction,
                onValueChange = { instruction = it },
                placeholder = "写给模型的做法，例如：先列出三个风险点，再给出改动建议。",
                minHeight = 160.dp,
            )
        }
        item {
            LibraryHint(
                title = null,
                detail = "指令会作为系统提示的一段注入这个会话的每一轮对话（以「## Skill」开头）；它不会执行代码，也不会出现在聊天气泡里。",
            )
        }
        if (!canSave) {
            item {
                LibraryHint(
                    title = null,
                    detail = "名称与指令都不能为空，填写后才能保存。",
                    tint = StatusError,
                )
            }
        }
        if (!isNew) {
            item {
                if (confirmingDelete) {
                    LibraryDeleteConfirm(
                        title = "确认删除「${draft.name.ifBlank { skill.name }}」？",
                        detail = "使用它的会话会恢复为无 Skill；已经写入的记忆与聊天记录不受影响。",
                        onConfirm = onDelete,
                        onCancel = { confirmingDelete = false },
                    )
                } else {
                    LibraryDeleteRow(
                        title = "删除 Skill",
                        summary = "使用它的会话会恢复为无 Skill",
                        onRequest = { confirmingDelete = true },
                    )
                }
            }
        }
    }
}
