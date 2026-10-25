package ev.buan.plugins.ev.ui.ui

import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class EvSuggestion(
    val label: String,
    val glyph: EvGlyphKind,
    /** Sent as the first message; empty means the suggestion is display-only. */
    val prompt: String,
)

/**
 * Empty state of the chat page, following Ev's home: one centered question, then a 2-column grid
 * of suggestion cards (16dp corner, hairline outline, 18dp glyph in the accent color).
 */
@Composable
fun EvHomeHero(
    suggestions: List<EvSuggestion>,
    onPick: (EvSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "有什么可以帮你？",
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            suggestions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { item ->
                        SuggestionCard(item, Modifier.weight(1f), onPick)
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: EvSuggestion,
    modifier: Modifier,
    onPick: (EvSuggestion) -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable { onPick(suggestion) }
            .padding(14.dp),
    ) {
        EvGlyph(
            suggestion.glyph,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = suggestion.label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}
