package ev.buan.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults

object EvCardDefaults {
    val CornerRadius = 24.dp
}

/** Grouped surface used by every settings-like page. 24dp radius matches the Eta look. */
@Composable
fun EvCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = EvCardDefaults.CornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        onClick = onClick,
        content = content,
    )
}
