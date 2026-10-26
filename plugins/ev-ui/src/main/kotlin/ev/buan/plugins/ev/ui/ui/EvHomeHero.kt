package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

data class EvSuggestion(
    val label: String,
    val glyph: EvGlyphKind,
    /** Sent as the first message; empty means the suggestion is display-only. */
    val prompt: String,
)

/**
 * Empty state of the chat page (spec §5.5): what this is, the ways in, where you left off.
 *
 * One continuous block, centred. The previous version split "start something" and "continue
 * something" with a flexible spacer, which on a 933dp-tall screen turned into 250dp of dead black
 * through the middle: not breathing room, a tear. The block is now whole and the leftover space
 * sits above and below it in equal measure.
 */
@Composable
fun EvHomeHero(
    suggestions: List<EvSuggestion>,
    onPick: (EvSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "有什么可以帮你？",
    /** e.g. "12 个工具 · 记忆已启用"; null hides the line. */
    status: String? = null,
    /** Most recent conversations, newest first (the caller trims this to three). */
    recents: List<EvSessionSummary> = emptyList(),
    onResume: (String) -> Unit = {},
) {
    val tokens = EvThemeTokens.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.8f))
        Text(
            text = title,
            style = EvThemeTokens.type.display.style(tokens.textPrimary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "问问任何事，或让 EV 帮你整理文件",
            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            suggestions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { item ->
                        SuggestionCard(item, Modifier.weight(1f), onPick)
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            // Recent conversations are one container with dividers, not three loose pills: they are
            // a single fact ("what I was doing"), and three separate cards would compete with the
            // four entry points above.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(EvThemeTokens.radii.mediumShape)
                    .background(tokens.surface1),
            ) {
                recents.forEachIndexed { index, session ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp, end = 14.dp)
                                .height(0.33.dp)
                                .background(tokens.dividerGhost),
                        )
                    }
                    RecentRow(session, onResume)
                }
            }
        }
        if (status != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = status,
                style = EvThemeTokens.type.micro.style(tokens.textTertiary),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionCard(
    suggestion: EvSuggestion,
    modifier: Modifier,
    onPick: (EvSuggestion) -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(EvThemeTokens.radii.mediumShape)
            .background(tokens.surface1)
            .clickable { onPick(suggestion) }
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EvGlyph(
            suggestion.glyph,
            color = tokens.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Body, not label: this text is the only content of the card.
            text = suggestion.label,
            style = EvThemeTokens.type.body.style(tokens.textPrimary),
        )
    }
}

/** One row inside the recents container: where you stopped, and when. */
@Composable
private fun RecentRow(session: EvSessionSummary, onResume: (String) -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "继续「${session.title}」", onClick = { onResume(session.id) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.title.ellipsize(20),
            style = EvThemeTokens.type.label.style(tokens.textPrimary),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (session.timeLabel.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.timeLabel,
                style = EvThemeTokens.type.micro.style(tokens.textTertiary),
            )
        }
    }
}
