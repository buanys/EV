package ev.buan.plugins.agent.browse

import android.app.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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
 * 地址栏有两种状态，和手机浏览器一致：
 *
 * - **浏览态**只显示主机名。整条 URL 在手机上会被截断在某个百分号上，
 *   而人想确认的只有一件事：我在哪个站。
 * - **编辑态**点一下就把完整地址填进来并全选——想换地址的时候，
 *   没人愿意先把旧地址删一遍。返回键先退出编辑态，再退页面。
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
        LoadProgress(progress = session.progress)
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
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme

    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }

    fun finishEditing() {
        editing = false
        session.editingAddress = false
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun submit() {
        session.address = field.text
        if (session.open(session.address)) finishEditing()
    }

    LaunchedEffect(editing) {
        session.editingAddress = editing
        if (editing) {
            // 全选：点地址栏的意图几乎总是"换掉整个地址"。
            field = TextFieldValue(
                text = session.currentUrl,
                selection = TextRange(0, session.currentUrl.length),
            )
            focus.requestFocus()
            keyboard?.show()
        }
    }

    // 编辑态下，返回键先收起键盘和选区，而不是直接离开这一页。
    if (editing) {
        BackHandler { finishEditing() }
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
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.onBackground.copy(alpha = 0.06f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 锁 = 这一页是加密的；信息图标 = 明文 http。
            // 两个都点得动：地址栏里唯一能回答"我在跟谁说话"的地方就是这里。
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "连接信息") {
                        showSiteInfo(context, session.currentUrl)
                    },
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(
                    if (session.secure) EvGlyphKind.Lock else EvGlyphKind.Info,
                    color = colors.onSurfaceVariantSummary.copy(alpha = 0.85f),
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            if (editing) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        session.address = it.text
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onBackground, fontSize = 13.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            } else {
                // Text 自己 fillMaxHeight 会从顶部开始画，必须由 Box 居中兜住，
                // 否则地址会贴在胶囊的上边缘、被裁掉一截。
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .clickable(onClickLabel = "编辑地址") { editing = true },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = displayAddressOf(session.currentUrl),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
 * 浏览态显示什么：主机名。
 *
 * 端口保留（`localhost:8080` 的端口和域名一样重要），路径和查询串丢掉——
 * 它们在手机上只会被截断成一段百分号，占着位置却不回答任何问题。
 */
private fun displayAddressOf(url: String): String {
    if (url.isBlank()) return ""
    val withoutScheme = url.substringAfter("://", url)
    val authority = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    return authority.ifBlank { withoutScheme }
}

/**
 * 地址栏下面那条 2dp 的加载线，宽度就是真实进度。
 *
 * 旧版是一条来回摆动的假动画：它只能回答"还活着吗"，回答不了"还要多久"。
 * 到 100 不立刻消失，而是淡出——瞬间消失会有一次视觉上的"断"。
 */
@Composable
private fun LoadProgress(progress: Int) {
    val active = progress in 1..99
    val fraction by animateFloatAsState(
        targetValue = if (active) progress / 100f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "fraction",
    )
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "alpha",
    )
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (alpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 连接信息。
 *
 * 手机浏览器的地址栏里点一下锁，看到的是"我在跟谁说话、这条线是不是被加密的"。
 * 这两件事都写在 URL 里，但人不会去读 URL。
 */
private fun showSiteInfo(context: android.content.Context, url: String) {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
        ?.removePrefix("www.")
        ?: url.substringAfter("://").substringBefore('/')
    val secure = url.startsWith("https://", ignoreCase = true)
    val message = buildString {
        append("网站：")
        append(host.ifBlank { "—" })
        append("\n\n连接：")
        append(if (secure) "加密（HTTPS）" else "未加密（HTTP），内容可能被网络中的设备看到")
        append("\n\n证书：")
        append(if (secure) "已由系统 WebView 校验通过" else "无")
    }
    AlertDialog.Builder(context)
        .setTitle("连接信息")
        .setMessage(message)
        .setPositiveButton("好", null)
        .show()
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
