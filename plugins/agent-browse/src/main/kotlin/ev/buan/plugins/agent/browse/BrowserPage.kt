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

            session.error?.let { message ->
                ErrorView(
                    message = message,
                    onRetry = {
                        session.clearError()
                        session.reload()
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } ?: run {
                if (session.currentUrl.isEmpty()) {
                    // 空态贴着顶部而不是屏幕正中：页面开起来之后这块会立刻被盖住，
                    // 放正中会让首屏看起来像"什么都没有"。
                    Text(
                        text = "输入网址开始浏览。链接会留在本面板内打开。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 32.dp, vertical = 44.dp),
                    )
                }
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
