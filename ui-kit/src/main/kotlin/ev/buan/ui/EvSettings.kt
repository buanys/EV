package ev.buan.ui

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward

/**
 * Grouped-card settings layout (spec §7.5): a quiet micro-size group label sits outside one
 * surface1 card; inside, rows carry a neutral surface2 glyph tile, a label/caption text stack
 * and a chevron or switch. Glyph tiles are deliberately colourless - the "one coloured tile per
 * row" look was the single largest source of visual noise, and semantic tints stay reserved for
 * genuinely destructive rows ([tileColor]).
 */
@Composable
fun EvSettingsSection(label: String) {
    Text(
        text = label,
        style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun EvSettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(EvThemeTokens.radii.mediumShape)
            .background(EvThemeTokens.colors.surface1),
        content = content,
    )
}

@Composable
fun EvSettingsRow(
    title: String,
    glyph: EvGlyphKind,
    tileColor: Color = Color.Unspecified,
    summary: String? = null,
    trailing: EvSettingsTrailing = EvSettingsTrailing.Chevron,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = EvThemeTokens.colors
    // Unspecified means the neutral tile; only destructive rows pass a semantic tint.
    val tinted = tileColor != Color.Unspecified
    val tileBackground = if (tinted) tileColor.copy(alpha = 0.16f) else tokens.surface2
    val glyphColor = if (tinted) tileColor else tokens.textSecondary
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(EvThemeTokens.radii.smallShape)
                .background(tileBackground),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(glyph, color = glyphColor, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = EvThemeTokens.type.label.style(tokens.textPrimary))
            if (summary != null) {
                Text(summary, style = EvThemeTokens.type.caption.style(tokens.textSecondary))
            }
        }
        when (trailing) {
            EvSettingsTrailing.Chevron -> Image(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(tokens.textTertiary),
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

/** In-group hairline: the ghost divider, indented to align with the row text, not the icon. */
@Composable
fun EvSettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 58.dp, end = 14.dp)
            .heightIn(min = 0.5.dp)
            .background(EvThemeTokens.colors.dividerGhost),
    )
}

/**
 * One switch for the whole product. Miuix owns the track, the thumb and the animation; the only
 * reason this wrapper exists is to keep the ui-kit surface stable for plugins.
 */
@Composable
fun EvSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}
