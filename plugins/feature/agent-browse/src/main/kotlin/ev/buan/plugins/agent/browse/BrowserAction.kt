package ev.buan.plugins.agent.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浏览器控制栏上的一个圆形图标按钮。
 *
 * 单独一个文件，是因为它只在浏览器里用，但被顶栏和地址栏两处复用；
 * 放进 BrowserPage 会让那个文件同时承担"页面组装"和"控件实现"。
 */
@Composable
internal fun RoundGlyphAction(
    glyph: EvGlyphKind,
    label: String,
    rotate: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvGlyph(
            glyph,
            color = if (enabled) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.onBackground.copy(alpha = 0.25f)
            },
            modifier = Modifier.size(17.dp).rotate(rotate),
        )
    }
}
