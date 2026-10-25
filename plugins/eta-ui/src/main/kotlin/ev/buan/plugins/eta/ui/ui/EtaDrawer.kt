package ev.buan.plugins.eta.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One conversation row in the drawer, already reduced to what the drawer renders. */
data class EtaSessionSummary(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/** Drawer history is grouped by local calendar day, newest group first. */
data class EtaHistoryGroup(
    val label: String,
    val sessions: List<EtaSessionSummary>,
)

/**
 * Eta's drawer, rebuilt from the screenshots rather than from Eta's implementation.
 *
 * Kept deliberately host-agnostic: it only knows "sessions", navigation callbacks and the current
 * search state. The plugin owns the data; this file owns the look.
 */
@Composable
fun EtaDrawerContent(
    groups: List<EtaHistoryGroup>,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onPlugins: () -> Unit,
    onModel: () -> Unit,
    onTools: () -> Unit,
    onBrowser: () -> Unit,
    onMemory: () -> Unit,
    onCharacters: () -> Unit,
    onSkills: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        DrawerHeader(onNewChat)
        if (searching) {
            SearchField(query, onQueryChange, onCancel = onSearch)
        } else {
            DrawerNav("插件中心", EvGlyphKind.Grid, onPlugins)
            DrawerNav("模型服务", EvGlyphKind.Sparkle, onModel)
            DrawerNav("工具", EvGlyphKind.Doc, onTools)
            DrawerNav("浏览器", EvGlyphKind.Globe, onBrowser)
            DrawerNav("记忆", EvGlyphKind.Memory, onMemory)
            DrawerNav("角色", EvGlyphKind.Person, onCharacters)
            DrawerNav("Skills", EvGlyphKind.Sparkle, onSkills)
            DrawerNav("设置", EvGlyphKind.Gear, onSettings)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "会话历史",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                Text(
                    text = if (searching && query.isNotBlank()) "没有匹配的对话" else "暂无会话",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                groups.forEach { group ->
                    Text(
                        text = group.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 3.dp),
                    )
                    group.sessions.forEach { session ->
                        HistoryItem(session, onSelectSession)
                    }
                }
            }
        }
        DrawerFooter(
            onSearch = onSearch,
            onSettings = onSettings,
            showSearch = !searching,
        )
    }
}

@Composable
private fun DrawerHeader(onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EtaLogo()
        Spacer(Modifier.width(12.dp))
        Text("EV", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        Badge("本地", MiuixTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Badge("插件", MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Chat, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EtaLogo() {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(Color(0xFF4C8DFF))
                Dot(Color(0xFFFF6B6B))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(Color(0xFFFFC857))
                Dot(Color(0xFF4CD97B))
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun Badge(text: String, tint: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

@Composable
private fun DrawerNav(label: String, glyph: EvGlyphKind, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(glyph, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
    }
}

@Composable
private fun HistoryItem(session: EtaSessionSummary, onSelect: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (session.selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            )
            .clickable { onSelect(session.id) }
            .padding(horizontal = 8.dp, vertical = 11.dp),
    ) {
        Text(
            text = session.title,
            style = MiuixTheme.textStyles.body1,
            color = if (session.selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(
            EvGlyphKind.Search,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 14.sp),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "搜索对话",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            "取消",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DrawerFooter(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    showSearch: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSearch) {
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvGlyph(
                    EvGlyphKind.Search,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "搜索对话",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
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
