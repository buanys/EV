package ev.buan.plugins.agent.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个浏览器面板：把顶栏、WebView、错误页和空态拼在一起。
 *
 * 这个文件只负责**组装**——状态在 [BrowserSession]，顶栏在 [BrowserChrome]，
 * WebView 与平台回调在 [BrowserWebView]，历史栏在 [BrowserBottomBar]。
 *
 * 用普通 [Column] 而不是 `EvPage`：EvPage 的页面槽是 LazyColumn，而 WebView 需要占满
 * 剩余高度并自己处理滚动。
 *
 * 范围：标签页浏览器。标签集合由**插件实例**持有（[BrowserTabs]），所以离开这个页面
 * 再回来，页面、JS 上下文与滚动位置都还在；销毁只发生在用户关标签与插件停用时。
 */
@Composable
internal fun BrowserPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标签集合由**插件实例**持有：离开这个页面再回来，页面还在（见 [BrowserTabs]）。 */
    tabs: BrowserTabs = remember { BrowserTabs() },
) {
    // 没有标签就开一个；返回时标签还在，所以这里通常什么都不做。
    if (tabs.tabs.isEmpty()) tabs.openTab()
    val session = tabs.active ?: return
    val keyboard = LocalSoftwareKeyboardController.current

    // 系统返回先走网页历史，没有历史才离开面板；聊天页自己也装了返回处理，
    // 这一层更深，所以浏览器打开时它优先。
    BackHandler {
        keyboard?.hide()
        if (!session.goBack()) onBack()
    }

    Column(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding()) {
        BrowserTabStrip(tabs = tabs, activeId = session.id)
        BrowserChrome(session = session, onBack = onBack)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // WebView 常驻：从组合里移除会触发 DisposableEffect 把它销毁，
            // 再打开页面又要重建一个。首页只是盖在它上面的一层。
            // key 到标签 id：切换标签时 AndroidView 必须重建，否则它继续显示上一个标签的
            // WebView（factory 只在首次创建时跑一次）。
            androidx.compose.runtime.key(session.id) {
                BrowserWebView(session = session, modifier = Modifier.fillMaxSize())
            }

            if (session.currentUrl.isEmpty() && session.error == null) {
                BrowserHome(
                    session = session,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background),
                )
            }

            session.error?.let { message ->
                ErrorView(
                    message = message,
                    onRetry = {
                        session.clearError()
                        session.reload()
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        BrowserBottomBar(session = session)
    }
}

/**
 * 加载失败时的页面。
 *
 * 旧版没有这个回调：断网或者域名打不开时就是一片白，用户不知道是网络、地址写错，
 * 还是应用坏了。这里至少要说清楚是哪一种。
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "打不开这个页面",
            style = MiuixTheme.textStyles.subtitle,
            color = colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.primary.copy(alpha = 0.12f))
                .clickable(onClickLabel = "重试", onClick = onRetry)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                text = "重试",
                style = MiuixTheme.textStyles.body1,
                color = colors.primary,
            )
        }
    }
}
