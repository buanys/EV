package ev.buan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text

/**
 * The header every second-level page wears: back, page name, one line about the page.
 *
 * Why this exists (2026-10-26 audit): pages used Miuix's `SmallTopAppBar`, which draws the page
 * name at the same size as a list row's title. Every second-level screen therefore topped out at
 * `type.title`, so leaving the home screen felt like nothing happened. This header promotes the
 * name to `type.headline` and gives it a quiet second line, which is the only place a page-level
 * fact belongs - "9 个 Provider", "5 个工具" used to float loose above the list, different on
 * every page.
 *
 * Layout is deliberately boring so it can be shared by the host and every plugin: a 48dp row
 * (back target, title, actions) plus an optional caption line. It never collapses on scroll -
 * a header that shrinks is a header that has to be re-learned per page.
 */
@Composable
fun EvPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = EvThemeTokens.colors
    val type = EvThemeTokens.type
    val indent = if (onBack != null) 52.dp else 20.dp

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(onClickLabel = "返回", onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    EvGlyph(
                        EvGlyphKind.Chevron,
                        color = colors.textPrimary,
                        modifier = Modifier.size(20.dp).rotate(180f),
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Text(
                text = title,
                style = type.headline.style(colors.textPrimary),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = type.caption.style(colors.textTertiary),
                maxLines = 1,
                modifier = Modifier.padding(start = indent, top = 1.dp, end = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Content padding for a page body under [EvPageHeader]: 4dp top so the first row sits close to
 * the header without touching it, and the same bottom breathing room everywhere.
 */
val EvPageBodyPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
