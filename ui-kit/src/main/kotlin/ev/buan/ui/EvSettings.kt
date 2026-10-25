package ev.buan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings layout from Eta: a small gray section label, then one rounded card holding rows that
 * each carry a coloured glyph, a title with optional subtitle, and either a chevron or a switch.
 */
@Composable
fun EvSettingsSection(label: String) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun EvSettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
fun EvSettingsRow(
    title: String,
    glyph: EvGlyphKind,
    tileColor: Color = MiuixTheme.colorScheme.primary,
    summary: String? = null,
    trailing: EvSettingsTrailing = EvSettingsTrailing.Chevron,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(tileColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(glyph, color = tileColor, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
            if (summary != null) {
                Text(
                    summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        when (trailing) {
            EvSettingsTrailing.Chevron -> EvGlyph(
                EvGlyphKind.Send,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(12.dp),
            )

            EvSettingsTrailing.Switch -> EvSwitch(
                checked = checked,
                onCheckedChange = { onCheckedChange?.invoke(it) },
            )

            EvSettingsTrailing.None -> Unit
        }
    }
}

enum class EvSettingsTrailing { Chevron, Switch, None }

@Composable
fun EvSettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 58.dp)
            .heightIn(min = 0.5.dp)
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f)),
    )
}

/** Minimal switch; Miuix's own is not part of the shared surface. */
@Composable
fun EvSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(
                if (checked) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.16f),
            )
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (checked) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground),
        )
    }
}
