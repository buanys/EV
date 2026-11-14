package ev.buan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    // 返回是一个"圆形入口"，不是一张贴边的图标：40dp 的表面给手指一个
                    // 明确的落点，标题则在视觉上从它旁边起笔。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(colors.surface2, CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = rememberVectorPainter(EvIcon(EvIcon.Back)),
                            contentDescription = "返回",
                            colorFilter = ColorFilter.tint(colors.textSecondary),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.size(14.dp))
                }
                EvText(title, kind = EvTextKind.Headline)
            }

            val scrolled = if (scrollable) {
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxSize()
            }
            Column(
                // 页面级留白：20dp 侧边 + 32dp 收尾。卡片内部不再各自加边，
                // 所有卡片因此左边缘对齐——这是"排过版"最简单的证据。
                modifier = scrolled.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }
}
