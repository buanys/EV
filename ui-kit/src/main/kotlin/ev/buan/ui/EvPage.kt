package ev.buan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * 二级页：标题 + 可选返回 + 可滚动内容。
 *
 * 与首页的区别是**有标题**：首页没有顶部栏（靠悬浮件），二级页需要一句话说明
 * "你在哪、能退回哪"。返回按钮就是那个出口，放在标题左边而不是右上角——
 * 手机上大拇指够得着的是左侧。
 *
 * 内容用 [ColumnScope]，不自带列表：页面自己决定是 `LazyColumn` 还是普通列。
 * 给一个 `verticalScroll` 的默认包装，够覆盖设置这类静态列表。
 */
@Composable
fun EvPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = EvColors
    EvPageScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Image(
                        painter = rememberVectorPainter(EvIcon(EvIcon.Back)),
                        contentDescription = "返回",
                        colorFilter = ColorFilter.tint(colors.textPrimary),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(onClick = onBack)
                            .padding(8.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                EvText(title, kind = EvTextKind.Headline)
            }

            val scrolled = if (scrollable) {
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxSize()
            }
            Column(
                modifier = scrolled.padding(bottom = 16.dp),
                verticalArrangement = Arrangement.Top,
                content = content,
            )
        }
    }
}
