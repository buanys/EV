package ev.buan.plugins.agent.browse

import androidx.compose.foundation.clickable
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部历史栏：后退、前进，以及当前页面的一句话状态。
 *
 * 放在底部而不是顶部有两个原因：拇指够得着；以及和顶部的「退出面板」分开——
 * 两件事不该长得一样，也不该挨在一起。
 */
@Composable
internal fun BrowserBottomBar(
    session: BrowserSession,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(BrowserArrowKind.ChevronLeft, session.canGoBack, "后退") { session.goBack() }
        NavArrow(BrowserArrowKind.ChevronRight, session.canGoForward, "前进") { session.goForward() }
        Spacer(Modifier.width(10.dp))
        Text(
            text = statusOf(session),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
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

/** 一行状态：正在加载 → 页面标题 → 地址 → 还没开始。 */
private fun statusOf(session: BrowserSession): String = when {
    session.loading -> "载入中…"
    session.pageTitle.isNotBlank() -> session.pageTitle
    session.currentUrl.isNotBlank() -> session.currentUrl
    // 还没打开过页面时留空：空态提示已经在页面中间说过一遍了，
    // 同一句话在底栏再说一次只是噪音。
    else -> ""
}
