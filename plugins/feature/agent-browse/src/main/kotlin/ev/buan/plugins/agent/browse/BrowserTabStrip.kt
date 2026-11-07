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
 * 放在地址栏上面而不是藏进菜单——多标签是这次强化的主功能，藏起来等于没做。
 * chip 上显示页面标题（没有就退到域名，再没有就是"新标签"），当前标签高亮，右侧 `×` 关闭。
 * 最后一个标签关不掉（[BrowserTabs.close] 保证关掉之后总有一页可显示）。
 */
@Composable
internal fun BrowserTabStrip(
    tabs: BrowserTabs,
    activeId: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEach { tab ->
            val active = tab.id == activeId
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.secondaryContainer else colors.surface)
                    .clickable { tabs.activate(tab.id) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.label(),
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Text(
                    text = "  ×",
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clickable { tabs.close(tab.id) }
                        .padding(horizontal = 4.dp),
                )
            }
        }
        Text(
            text = "  +",
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier
                .clickable { tabs.openTab() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 一眼能认出这是哪一页：标题 > 域名 > "新标签"。 */
private fun BrowserSession.label(): String {
    pageTitle.takeIf { it.isNotBlank() }?.let { return it }
    currentUrl.takeIf { it.isNotBlank() }?.let { url ->
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.let { return it }
    }
    return "新标签"
}
