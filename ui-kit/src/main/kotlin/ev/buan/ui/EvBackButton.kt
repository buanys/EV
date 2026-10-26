package ev.buan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Back affordance used by [EvPage]; kept separate so pages can place it themselves. */
@Composable
fun EvBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clickable(onClickLabel = "返回", onClick = onClick).padding(12.dp)) {
        // The same 2dp chevron every other disclosure in the app uses, turned around: a heavier
        // system arrow next to 26 drawn glyphs reads as a leftover.
        EvGlyph(EvGlyphKind.Chevron, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp).rotate(180f))
    }
}
