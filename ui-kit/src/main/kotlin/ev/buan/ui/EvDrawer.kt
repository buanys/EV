package ev.buan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

object EvDrawerDefaults {
    val Width = 300.dp
    val NavItemHeight = 52.dp
}

data class EvDrawerItem(
    val label: String,
    val glyph: EvGlyphKind,
    val badge: String? = null,
    val onClick: () -> Unit,
)

data class EvHistoryGroup(val label: String, val titles: List<String>, val selected: String? = null)

/**
 * Slide-over navigation, following Eta's drawer: identity header, icon rows with optional badges,
 * date-grouped conversation history, and a footer that holds the character plus search/settings.
 */
@Composable
fun EvDrawerScaffold(
    open: Boolean,
    onClose: () -> Unit,
    drawer: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Column(
                Modifier.fillMaxHeight()
                    .width(EvDrawerDefaults.Width)
                    .background(MiuixTheme.colorScheme.background),
                content = drawer,
            )
        }
    }
}

@Composable
fun EvDrawerHeader(title: String, badge: String, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Sparkle, color = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(badge, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Chat, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun EvDrawerNavItem(item: EvDrawerItem) {
    Row(
        Modifier.fillMaxWidth().height(EvDrawerDefaults.NavItemHeight)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(item.glyph, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            item.label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (item.badge != null) {
            Box(
                Modifier.clip(CircleShape).background(MiuixTheme.colorScheme.primary)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(item.badge, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun EvDrawerSection(label: String) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun EvDrawerHistory(groups: List<EvHistoryGroup>) {
    groups.forEach { group ->
        Text(
            text = group.label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        group.titles.forEach { title ->
            val selected = title == group.selected
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Footer: current identity, then the search pill and settings affordance. */
@Composable
fun EvDrawerFooter(identity: String, onSearch: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("E", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Text(identity, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EvGlyph(EvGlyphKind.Search, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
                Text(
                    "搜索对话",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(EvGlyphKind.Gear, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
            }
        }
    }
}
