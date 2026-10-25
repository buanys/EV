package ev.buan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

object EvPreferenceDefaults {
    val SidePadding = 16.dp
    val GroupSpacing = 16.dp
    val RowMinHeight = 52.dp
    val ContentStart = 16.dp
}

@Composable
fun EvPreferenceGroup(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    EvCard(
        modifier = modifier
            .padding(horizontal = EvPreferenceDefaults.SidePadding)
            .padding(bottom = EvPreferenceDefaults.GroupSpacing),
        insideMargin = insideMargin,
        colors = colors,
        content = content,
    )
}

@Composable
fun EvGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
    )
}

/**
 * One settings line: optional leading slot, title/summary stack, trailing slot.
 * Deliberately simpler than a full Miuix preference: no icon optical correction, no RTL flip.
 */
@Composable
fun EvPreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    val titleColor = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = EvPreferenceDefaults.RowMinHeight)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = EvPreferenceDefaults.SidePadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startAction != null) {
            startAction()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.body1, color = titleColor)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        endActions()
    }
}

@Composable
fun EvPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = if (hasLeading) EvPreferenceDefaults.ContentStart else EvPreferenceDefaults.SidePadding,
            end = EvPreferenceDefaults.SidePadding,
        ),
        thickness = 0.33.dp,
        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
    )
}
