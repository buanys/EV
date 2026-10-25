package ev.buan.plugins.ev.ui.ui

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
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/** One conversation row in the drawer, already reduced to what the drawer renders. */
data class EvSessionSummary(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/** Drawer history is grouped by local calendar day, newest group first. */
data class EvHistoryGroup(
    val label: String,
    val sessions: List<EvSessionSummary>,
)

/**
 * The drawer, three sections per spec §7.4: an identity block (logo, name, one status line),
 * the capability rows whose glyphs each mean exactly one thing (spec §4), and the date-grouped
 * session history with the accent bar marking the current one. Search stays pinned to the
 * bottom where the thumb already is.
 *
 * Kept deliberately host-agnostic: it only knows "sessions", navigation callbacks and the current
 * search state. The plugin owns the data; this file owns the look.
 */
@Composable
fun EvDrawerContent(
    groups: List<EvHistoryGroup>,
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
    statusLine: String = "本地 · 插件宿主",
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        DrawerHeader(statusLine, onNewChat)
        if (searching) {
            SearchField(query, onQueryChange, onCancel = onSearch)
        } else {
            DrawerNav("插件中心", EvGlyphKind.Grid, onPlugins)
            DrawerNav("模型服务", EvGlyphKind.Atom, onModel)
            DrawerNav("工具", EvGlyphKind.Tool, onTools)
            DrawerNav("浏览器", EvGlyphKind.Globe, onBrowser)
            DrawerNav("记忆", EvGlyphKind.Memory, onMemory)
            DrawerNav("角色", EvGlyphKind.Person, onCharacters)
            DrawerNav("Skills", EvGlyphKind.Sparkle, onSkills)
            DrawerNav("设置", EvGlyphKind.Gear, onSettings)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "会话历史",
            style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                Text(
                    text = if (searching && query.isNotBlank()) "没有匹配的对话" else "暂无会话",
                    style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                groups.forEach { group ->
                    Text(
                        text = group.label,
                        style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
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
private fun DrawerHeader(statusLine: String, onNewChat: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvLogo()
        Spacer(Modifier.width(12.dp))
        Column {
            Text("EV", style = EvThemeTokens.type.title.style(tokens.textPrimary))
            Text(statusLine, style = EvThemeTokens.type.caption.style(tokens.textSecondary))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(tokens.surface2)
                .clickable(onClickLabel = "新会话", onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Chat, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EvLogo() {
    Box(
        Modifier.size(40.dp).clip(EvThemeTokens.radii.smallShape)
            .background(EvThemeTokens.colors.surface2),
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
private fun DrawerNav(label: String, glyph: EvGlyphKind, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(glyph, color = tokens.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = EvThemeTokens.type.label.style(tokens.textPrimary))
    }
}

@Composable
private fun HistoryItem(session: EvSessionSummary, onSelect: (String) -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(EvThemeTokens.radii.smallShape)
            .background(if (session.selected) tokens.accentSoft else Color.Transparent)
            .clickable { onSelect(session.id) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The 3dp accent bar marks the current session (spec §7.4).
        Box(
            Modifier.width(3.dp).height(16.dp)
                .clip(CircleShape)
                .background(if (session.selected) tokens.accent else Color.Transparent),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = session.title,
            style = EvThemeTokens.type.label.style(
                if (session.selected) tokens.accent else tokens.textPrimary,
            ),
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
    val tokens = EvThemeTokens.colors
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(40.dp)
            .clip(EvThemeTokens.radii.fullShape)
            .background(tokens.surface2)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(EvGlyphKind.Search, color = tokens.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = EvThemeTokens.type.label.style(tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accent),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "搜索对话",
                            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            "取消",
            style = EvThemeTokens.type.caption.style(tokens.accent),
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
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSearch) {
            Row(
                Modifier.weight(1f).height(40.dp).clip(EvThemeTokens.radii.fullShape)
                    .background(tokens.surface2)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvGlyph(EvGlyphKind.Search, color = tokens.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索对话", style = EvThemeTokens.type.caption.style(tokens.textTertiary))
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(tokens.surface2)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Gear, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}
