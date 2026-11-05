package ev.buan.plugins.agent.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浏览器顶栏：返回、地址栏、Go，以及前进/后退/刷新和状态文字。
 *
 * 它只负责"看起来怎样"和"点了调哪个方法"，所有状态都来自 [BrowserSession]。
 * 把这一步分开之后，地址栏的输入法行为不会再和 WebView 的生命周期纠缠在一起。
 */
@Composable
internal fun BrowserChrome(
    session: BrowserSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun submit() {
        if (session.open(session.address)) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Row(
        modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundGlyphAction(EvGlyphKind.Send, "返回聊天", rotate = -90f, onClick = onBack)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (session.address.isEmpty()) {
                Text(
                    text = "输入网址",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                value = session.address,
                onValueChange = { session.address = it },
                singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 13.sp),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { session.editingAddress = it.isFocused },
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Go",
            style = MiuixTheme.textStyles.body1,
            color = if (session.address.isBlank()) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
            modifier = Modifier
                .clickable(enabled = session.address.isNotBlank(), onClickLabel = "打开地址") { submit() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundGlyphAction(EvGlyphKind.Send, "后退", rotate = -90f, enabled = session.canGoBack) {
            session.goBack()
        }
        RoundGlyphAction(EvGlyphKind.Send, "前进", rotate = 90f, enabled = session.canGoForward) {
            session.goForward()
        }
        RoundGlyphAction(EvGlyphKind.Refresh, "刷新", enabled = session.currentUrl.isNotEmpty()) {
            session.reload()
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusOf(session),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 一行状态：正在加载 → 页面标题 → 地址 → 还没开始。 */
private fun statusOf(session: BrowserSession): String = when {
    session.loading -> "载入中…"
    session.pageTitle.isNotBlank() -> session.pageTitle
    session.currentUrl.isNotBlank() -> session.currentUrl
    else -> "输入网址开始浏览"
}
