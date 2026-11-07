package ev.buan.plugins.agent.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浏览器的首页（新标签页）。
 *
 * 参考手机上正常浏览器的结构：没有页面时，顶栏让位给标题，中间是一个大搜索框，
 * 底部还是那排历史按钮。旧版把地址栏常驻在顶栏，空白时只有一句提示，
 * 看起来像"一个还没填地址的输入框"而不是一个可以开始的地方。
 *
 * 常用站点还没做——那需要收藏或访问统计，属于另一件事，先不放假图标。
 */
@Composable
internal fun BrowserHome(
    panel: BrowserPanel,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun submit() {
        if (normalizeUrl(panel.address) == null) return
        panel.open(panel.address)
        focusManager.clearFocus()
        keyboard?.hide()
    }

    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 把搜索框推到偏上的位置：太居中会显得空，太靠上又像工具栏。
        Spacer(Modifier.weight(0.38f))

        // 字标而不是图标：Atom 在 56dp 下就是一个十字准星，读不出品牌；
        // 这个面板也不需要很重的品牌存在感，几个字母足够当锚点。
        Text(
            text = "EV",
            style = TextStyle(
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
            ),
            color = colors.onBackground.copy(alpha = 0.55f),
        )

        Spacer(Modifier.height(28.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.onBackground.copy(alpha = 0.06f))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvGlyph(
                EvGlyphKind.Search,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (panel.address.isEmpty()) {
                    Text(
                        text = "输入网址或搜索",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = panel.address,
                    onValueChange = { panel.address = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onBackground, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (panel.address.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "前往",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.primary,
                    modifier = Modifier
                        .clickable(onClickLabel = "打开地址") { submit() }
                        .padding(vertical = 4.dp),
                )
            }
        }

        // 访问过的站点。第一眼看是空的，用过几次之后这里就变成了入口——
        // 比硬编码一排默认站点诚实。
        if (panel.recentHosts.isNotEmpty()) {
            Spacer(Modifier.height(30.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                panel.recentHosts.forEach { host ->
                    SiteChip(
                        host = host,
                        onClick = { panel.open(host) },
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.62f))
    }
}

/** 首页的一个站点入口：圆形首字母 + 域名。 */
@Composable
private fun SiteChip(host: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        Modifier
            .width(72.dp)
            .clickable(onClickLabel = "打开 $host", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = host.firstOrNull()?.uppercase() ?: "?",
                style = MiuixTheme.textStyles.subtitle,
                color = colors.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = host,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
