package ev.buan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/**
 * The one empty state. Every list that can be empty shows this, at the same place, with the same
 * three parts: what is missing, why, and - optionally - the way out.
 *
 * Why it exists (2026-10-26 audit): five panels each invented their own. Characters and Skills put
 * a left-aligned hint under an explainer line, Memory centred its text with no explainer, and the
 * browser pinned a single sentence at 78% of the screen height with no title at all. Same product,
 * five different answers to "this is empty".
 *
 * Anchored to the top of the content area rather than centred on the screen: a panel that centres
 * its own emptiness reads as a different kind of app from the list it turns into once filled.
 */
@Composable
fun EvEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    glyph: EvGlyphKind? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp)) {
        if (glyph != null) {
            EvGlyph(glyph, color = colors.textTertiary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(14.dp))
        }
        Text(text = title, style = type.title.style(colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        Text(text = detail, style = type.caption.style(colors.textTertiary))
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
