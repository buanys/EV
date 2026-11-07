package ev.buan.plugins.agent.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部历史栏：后退、前进，以及一颗回首页。
 *
 * 放在底部而不是顶部有两个原因：拇指够得着；以及和顶部的「退出面板」分开——两件事不该
 * 长得一样，也不该挨在一起。
 *
 * "能不能后退"读的是宿主快照：`WebView.canGoBack() || history.length > 1`。
 * 后半截不能少——agent 的点击是 `location.href` 驱动的，那条路上 WebView 认为没有历史。
 */
@Composable
internal fun BrowserBottomBar(
    panel: BrowserPanel,
    modifier: Modifier = Modifier,
) {
    val tab = panel.active
    Row(
        modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        NavArrow(BrowserArrowKind.ChevronLeft, tab?.canGoBack == true, "后退") { panel.back() }
        Spacer(Modifier.width(28.dp))
        NavArrow(BrowserArrowKind.ChevronRight, tab?.canGoForward == true, "前进") {
            panel.forward()
        }
        Spacer(Modifier.width(28.dp))
        // 回首页：正常浏览器的底部都有这一颗，否则离开页面之后没有"起点"可回。
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "回首页", onClick = { panel.goHome() }),
            contentAlignment = Alignment.Center,
        ) {
            BrowserHomeGlyph(
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NavArrow(
    kind: BrowserArrowKind,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val color = if (enabled) {
        MiuixTheme.colorScheme.onBackground
    } else {
        MiuixTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BrowserArrow(kind = kind, color = color, modifier = Modifier.size(18.dp))
    }
}
