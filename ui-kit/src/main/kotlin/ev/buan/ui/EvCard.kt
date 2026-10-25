package ev.buan.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults

/**
 * Grouped surface used by every settings-like page (spec §7.5): surface1 fill, medium radius.
 * Content colour follows the token text hierarchy instead of the raw Miuix scheme.
 */
@Composable
fun EvCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(
        color = EvThemeTokens.colors.surface1,
        contentColor = EvThemeTokens.colors.textPrimary,
    ),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = EvThemeTokens.radii.medium,
        insideMargin = insideMargin,
        colors = colors,
        onClick = onClick,
        content = content,
    )
}
