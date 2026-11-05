package ev.buan.plugins.agent.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个浏览器面板：把顶栏、WebView 和空态拼在一起。
 *
 * 这个文件只负责**组装**——状态在 [BrowserSession]，顶栏在 [BrowserChrome]，
 * WebView 在 [BrowserWebView]。之前它们全挤在这个函数里，想加一个能力就得
 * 在一百多行布局中间再塞一块。
 *
 * 用普通 [Column] 而不是 `EvPage`：EvPage 的页面槽是 LazyColumn，而 WebView 需要占满
 * 剩余高度并自己处理滚动。
 *
 * 范围刻意很小：打开一个页面看看，不是标签页浏览器——链接留在面板里，不拉起外部应用，
 * WebView 随面板销毁，所以关掉之后不保留页面、JS 上下文或定时器。
 */
@Composable
fun BrowserPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = remember { BrowserSession() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 系统返回先走网页历史，没有历史才离开面板；聊天页自己也装了返回处理，
    // 这一层更深，所以浏览器打开时它优先。
    BackHandler {
        keyboard?.hide()
        if (!session.goBack()) onBack()
    }

    Column(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding()) {
        BrowserChrome(session = session, onBack = onBack)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowserWebView(session = session, modifier = Modifier.fillMaxSize())

            if (session.currentUrl.isEmpty()) {
                Text(
                    text = "地址栏输入网址后按 Go；链接会留在本面板内打开。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                )
            }
        }
    }
}
