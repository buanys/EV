package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyphKind
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
    onDeleteSession: (String) -> Unit,
    onPlugins: () -> Unit,
    onModel: () -> Unit,
    onTools: () -> Unit,
    onBrowser: () -> Unit,
    onMemory: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    statusLine: String = "本地 · 插件宿主",
    moreExpanded: Boolean = false,
    onToggleMore: () -> Unit = {},
) {
    // 待删除的会话。放在确认框里而不是一按就删：会话一旦删掉，里面的对话就没了。
    var pendingDelete by remember { mutableStateOf<EvSessionSummary?>(null) }
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        DrawerHeader(statusLine, onSettings)
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
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "会话历史",
            style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
        )
        // LazyColumn 而不是 Column + forEach：会话一多，后者既不滚动也不会回收，
        // 列表尾部直接长到屏幕外，用户看着就是「划不动」。
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        text = if (searching && query.isNotBlank()) "没有匹配的对话" else "暂无会话",
                        style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                groups.forEach { group ->
                    item(key = "label-" + group.label) {
                        Text(
                            text = group.label,
                            style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
                            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 3.dp),
                        )
                    }
                    items(group.sessions, key = { it.id }) { session ->
                        HistoryItem(
                            session = session,
                            onSelect = onSelectSession,
                            onRequestDelete = { pendingDelete = session },
                        )
                    }
                }
            }
        }
        DrawerFooter(
            onSearch = onSearch,
            showSearch = !searching,
        )
    }
    pendingDelete?.let { victim ->
        DeleteSessionDialog(
            title = victim.title,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDeleteSession(victim.id)
                pendingDelete = null
            },
        )
    }
    }
}
