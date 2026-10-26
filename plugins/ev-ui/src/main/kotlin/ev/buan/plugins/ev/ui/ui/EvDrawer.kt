package ev.buan.plugins.ev.ui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * Truncation with a real ellipsis, measured in "display units" with CJK counted double.
 *
 * miuix's Text ignores TextOverflow.Ellipsis: it hard-clips the string, and in a 14sp Chinese title
 * the cut reads as a stray vertical mark. Character counting is crude, but it is honest about width
 * in a way that `take(18)` never was.
 */
internal fun String.ellipsize(maxUnits: Int): String {
    if (isEmpty()) return this
    var units = 0
    val kept = StringBuilder()
    for (ch in this) {
        units += if (ch.code >= 0x2E80) 2 else 1
        if (units > maxUnits) break
        kept.append(ch)
    }
    return if (kept.length == length) this else kept.toString().trimEnd() + "…"
}

/** One conversation row in the drawer, already reduced to what the drawer renders. */
data class EvSessionSummary(
    val id: String,
    val title: String,
    val selected: Boolean,
    /** HH:mm of the last turn: two conversations about the same thing are otherwise identical rows. */
    val timeLabel: String = "",
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
    moreExpanded: Boolean = false,
    onToggleMore: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        DrawerHeader(statusLine, onNewChat)
        if (searching) {
            SearchField(query, onQueryChange, onCancel = onSearch)
        } else {
            // 常驻区：每次会话都可能用到的三件事，一步可达（spec §5.1 L1）。
            DrawerNav("插件中心", EvGlyphKind.Grid, onPlugins)
            DrawerNav("模型服务", EvGlyphKind.Atom, onModel)
            DrawerNav("工具", EvGlyphKind.Tool, onTools)
            // 配置区：一周用不上一次的入口收进一组，抽屉才有"主次"而不是八个平等按钮。
            DrawerMore(expanded = moreExpanded, onToggle = onToggleMore) {
                DrawerSubNav("浏览器", EvGlyphKind.World, onBrowser)
                DrawerSubNav("记忆", EvGlyphKind.Memory, onMemory)
                DrawerSubNav("角色", EvGlyphKind.Person, onCharacters)
                DrawerSubNav("Skills", EvGlyphKind.Skill, onSkills)
                DrawerSubNav("设置", EvGlyphKind.Gear, onSettings)
            }
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

/**
 * The mark: four dots in one hue rather than four different colours.
 *
 * In a page whose whole discipline is "one accent, used sparingly" (spec §1.3), a red-blue-yellow-
 * green cluster was the loudest thing on screen and it meant nothing. Same shape, one voice.
 */
@Composable
private fun EvLogo() {
    val ink = EvThemeTokens.colors.textPrimary
    Box(
        Modifier.size(40.dp).clip(EvThemeTokens.radii.smallShape)
            .background(EvThemeTokens.colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Four equal dots. Mixing alphas made one dot read as a status light and the other
            // three as debris; a uniform 2x2 grid at least reads as a mark.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(ink.copy(alpha = 0.9f))
                Dot(ink.copy(alpha = 0.9f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(ink.copy(alpha = 0.9f))
                Dot(ink.copy(alpha = 0.9f))
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

/**
 * The collapsible "更多工具" group.
 *
 * Everything below [DrawerNav]'s three rows is configured once and then forgotten, so it does not
 * deserve the same visual weight as the daily entries (spec §5.1, L2 = 两步可达). Collapsing them
 * also gives the session history its room back: five equal rows pushed the list down for features
 * nobody opens during a conversation.
 *
 * The caret is one glyph rotating 90° rather than two glyphs swapping places, so the shape stays
 * continuous as the group opens (spec §6.2).
 */
@Composable
private fun DrawerMore(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    Row(
        Modifier.fillMaxWidth()
            .height(40.dp)
            .clickable(
                onClickLabel = if (expanded) "收起更多工具" else "展开更多工具",
                onClick = onToggle,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The caret rides right after the label instead of being pushed to the far edge: parked at
        // the right it lines up with the history timestamps and the row reads as another heading.
        Text(
            text = "更多工具",
            style = EvThemeTokens.type.caption.style(tokens.textSecondary),
        )
        Spacer(Modifier.width(6.dp))
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            label = "moreCaret",
        )
        EvGlyph(
            EvGlyphKind.Chevron,
            color = tokens.textTertiary,
            modifier = Modifier.size(12.dp).rotate(rotation),
        )
        Spacer(Modifier.weight(1f))
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(EvMotionTokens.fadeFast()) + expandVertically(
            EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
        ),
        exit = fadeOut(EvMotionTokens.fadeFast()) + shrinkVertically(
            EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
        ),
    ) {
        Column(content = content)
    }
}

/**
 * A row inside [DrawerMore]: indented under its parent and one step quieter, so the nesting is
 * legible without drawing a guide line (spec §0: depth comes from tone, not from rules).
 */
@Composable
private fun DrawerSubNav(label: String, glyph: EvGlyphKind, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(start = 52.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(glyph, color = tokens.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = EvThemeTokens.type.label.style(tokens.textSecondary))
    }
}

@Composable
private fun HistoryItem(session: EvSessionSummary, onSelect: (String) -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(EvThemeTokens.radii.smallShape)
            .background(if (session.selected) tokens.accent.copy(alpha = 0.12f) else Color.Transparent)
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
            text = session.title.ellipsize(24),
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

/**
 * Footer: the search pill, full width.
 *
 * The gear that used to sit here is gone on purpose - 设置 now lives inside 更多工具, and two
 * entries for one destination is exactly the kind of noise the drawer was reorganised to remove.
 */
@Composable
private fun DrawerFooter(
    onSearch: () -> Unit,
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
        }
    }
}
