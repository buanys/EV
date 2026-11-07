package ev.buan.plugins.agent.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 标签条：一行 chip + 一个 `+`。
 *
 * 放在地址栏上面而不是藏进菜单——多窗口是这次强化的主功能，藏起来等于没做。
 * chip 上显示页面标题（没有就退到域名，再没有就是"新标签"），当前窗口高亮，右侧 `×` 关闭。
 *
 * 数据直接来自 [BrowserPanel.snapshot]——也就是**宿主**的窗口列表。界面不再自己记
 * "我开过哪些标签"，因此 agent 开的窗口会立刻出现在这里，反之亦然。
 *
 * 窗口满了 `+` 变灰：一个窗口就是一个 WebView，允许无限开等于让用户替我们做内存压测。
 */
@Composable
internal fun BrowserTabStrip(
    panel: BrowserPanel,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val snapshot = panel.snapshot
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshot.tabs.forEach { tab ->
            val active = tab.id == snapshot.activeId
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) colors.secondaryContainer else colors.onBackground.copy(alpha = 0.06f),
                    )
                    .clickable { panel.selectTab(tab.id) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Text(
                    text = "  ×",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clickable { panel.closeTab(tab.id) }
                        .padding(horizontal = 4.dp),
                )
            }
        }
        val room = snapshot.tabs.size < snapshot.maxTabs
        Text(
            text = "  +",
            style = MiuixTheme.textStyles.footnote1,
            color = if (room) colors.onBackground else colors.onBackground.copy(alpha = 0.25f),
            modifier = Modifier
                .clickable(enabled = room) { panel.newTab() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
