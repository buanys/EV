package ev.buan.plugins.agent.browse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏。两种形态：
 *
 * - **首页**（还没有页面）：只有退出和标题「主页」。地址栏让位给中间的大搜索框——
 *   一个还没填地址的输入框贴在顶上，看起来像表单，不像"可以开始的地方"。
 * - **页面中**：退出 + 地址栏 + 刷新。
 *
 * 不管哪种形态，下方都跟着那条 2dp 的加载线。
 */
@Composable
internal fun BrowserChrome(
    session: BrowserSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (session.currentUrl.isEmpty()) {
            HomeHeader(onBack = onBack)
        } else {
            PageHeader(session = session, onBack = onBack)
        }
        LoadProgress(visible = session.loading)
    }
}

@Composable
private fun HomeHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleTap(enabled = true, label = "返回聊天", onClick = onBack) {
            BrowserArrow(
                kind = BrowserArrowKind.Back,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "主页",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun PageHeader(session: BrowserSession, onBack: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MiuixTheme.colorScheme

    fun submit() {
        if (session.open(session.address)) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleTap(enabled = true, label = "返回聊天", onClick = onBack) {
            BrowserArrow(
                kind = BrowserArrowKind.Back,
                color = colors.onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.onBackground.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (session.address.isEmpty()) {
                Text(
                    text = "输入网址",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                value = session.address,
                onValueChange = { session.address = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 13.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { session.editingAddress = it.isFocused },
            )
        }
        Spacer(Modifier.width(6.dp))
        CircleTap(
            enabled = session.currentUrl.isNotEmpty(),
            label = "刷新",
            onClick = { session.reload() },
        ) {
            EvGlyph(
                EvGlyphKind.Refresh,
                color = if (session.currentUrl.isNotEmpty()) {
                    colors.onBackground
                } else {
                    colors.onBackground.copy(alpha = 0.25f)
                },
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * 地址栏下面那条 2dp 的进度线。
 *
 * 旧版只有一句「载入中…」文字，页面慢的时候看不出到底在动还是卡住了。
 * 它不报告真实进度（WebView 的 onProgressChanged 才有），但能回答"还活着吗"。
 */
@Composable
private fun LoadProgress(visible: Boolean) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (!visible) return@Box
        val transition = rememberInfiniteTransition(label = "load")
        val fraction by transition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "fraction",
        )
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(MiuixTheme.colorScheme.primary))
    }
}

@Composable
private fun CircleTap(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
