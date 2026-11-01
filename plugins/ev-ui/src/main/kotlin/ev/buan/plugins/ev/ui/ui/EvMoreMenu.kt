package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * The right-hand quick menu: four square tiles that open a tool directly.
 *
 * Same physics as the navigation drawer (it slides in from the other edge), because it is the same
 * kind of surface: something that covers the conversation for a moment and closes on a tap outside.
 * Layout is fixed at 2 x 2 - a menu that grows a column per feature stops being glanceable, which is
 * the only thing a quick menu is for.
 */
@Composable
fun EvMoreMenuPanel(
    onTerminal: () -> Unit,
    onBrowser: () -> Unit,
    onPlaceholderOne: () -> Unit,
    onPlaceholderTwo: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "快捷工具",
            style = EvThemeTokens.type.title.style(tokens.textPrimary),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "不需要对话，直接打开",
            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EvMoreTile("终端", EvGlyphKind.Tool, Modifier.weight(1f), onTerminal)
            EvMoreTile("浏览器", EvGlyphKind.Globe, Modifier.weight(1f), onBrowser)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EvMoreTile("待定", EvGlyphKind.Grid, Modifier.weight(1f), onPlaceholderOne)
            EvMoreTile("待定", EvGlyphKind.Sparkle, Modifier.weight(1f), onPlaceholderTwo)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One square tile: glyph above label, the pair centred in a rounded surface. */
@Composable
private fun EvMoreTile(
    label: String,
    glyph: EvGlyphKind,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
            .background(tokens.surface2)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EvGlyph(glyph, color = tokens.textPrimary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = EvThemeTokens.type.label.style(tokens.textPrimary),
            )
        }
    }
}
