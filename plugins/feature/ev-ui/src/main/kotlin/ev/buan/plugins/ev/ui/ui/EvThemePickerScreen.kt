package ev.buan.plugins.ev.ui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing

/**
 * Theme picker: lists every theme the registry offers and applies the tapped one.
 *
 * The selection is written by the caller through the host-owned appearance state, so it persists
 * and reaches every plugin without this screen owning any theme data.
 */
@Composable
fun EvThemePickerScreen(
    /** Every selectable theme, already merged by the host catalog. */
    themes: List<ev.buan.ui.EvThemeSpec>,
    currentId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EvPage(
        title = "主题",
        modifier = modifier,
        onBack = onBack,
    ) {
        item { EvSettingsSection("可选主题") }
        item {
            EvSettingsGroup {
                themes.forEachIndexed { index, theme ->
                    if (index > 0) EvSettingsDivider()
                    val selected = theme.id == currentId
                    EvSettingsRow(
                        title = theme.name,
                        glyph = EvGlyphKind.Theme,
                        summary = if (selected) "当前使用 · ${theme.description}" else theme.description,
                        trailing = EvSettingsTrailing.None,
                        onClick = { onSelect(theme.id) },
                    )
                }
            }
        }
    }
}
