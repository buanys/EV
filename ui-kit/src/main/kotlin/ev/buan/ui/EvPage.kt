package ev.buan.ui

import com.itos.coui.text.CouiTextKind

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButton

/**
 * 二级页：COUI 的 `TopAppBar` + 可滚动内容。
 *
 * 和首页的区别是**有标题栏**：首页没有固定栏（靠悬浮件），二级页需要一句话说明
 * "你在哪、能退回哪"。栏本身、玻璃底、状态栏内边距、滚动时的淡化全部来自 COUI 的
 * [TopAppBar]，这里一行都不自己画——之前手搓的 `Row + 圆形返回按钮 + 垂直留白`
 * 有三处和 COUI 的字、材质、高度对不上，一进页面就能看出来。
 *
 * 滚动用 COUI 的 [couiVerticalScroll]：到达边界时的阻尼回弹是它材质语言的一部分，
 * `verticalScroll` 的滚动默认是硬边框，滑到底会"啪"地停住。
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
    EvPageScaffold(
        modifier = modifier,
        topBar = { backdrop ->
            TopAppBar(
                Modifier.fillMaxWidth(),
                {
                    // 顶栏用 COUI 的 Toolbar 角色。之前用 PageTitle，那是"页面里的大标题"
                    // 字号——放进 TopAppBar 后被放成一行 40 多磅的字，比内容还抢。
                    // 顶栏是一句话说明"你在哪"，不是这一屏的主角。
                    EvText(title, kind = CouiTextKind.Toolbar, weight = FontWeight.Bold)
                },
                {
                    if (onBack != null) {
                        TopAppBarButton(onClick = onBack) {
                            Image(
                                painter = rememberVectorPainter(EvIcon(EvIcon.Back)),
                                contentDescription = "返回",
                                colorFilter = ColorFilter.tint(colors.textPrimary),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                backdrop = backdrop,
            )
        },
    ) {
        val scrolled = if (scrollable) {
            Modifier.fillMaxSize().couiVerticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxSize()
        }
        Column(
            // 页面的三处留白全部取自 COUI 自己的一套常量：
            // 左右边距、分组间距、收尾高度。之前是 20dp / 20dp / 32dp 三个手写的数，
            // 和 COUI 的 `Group` 自带的内边距叠在一起，分组之间就空出一大块——
            // 同一个页面上"分组之间"和"分组内部"的呼吸比例，只有库自己知道。
            // 水平方向不在这里加边距：二级页的内容全是 `Group`，它自己带页面边距；
            // 这里再加一层，卡片会比首页的列表窄 200 多 px，两个页面的内容宽度对不上。
            modifier = scrolled.padding(bottom = CouiPageDefaults.groupSpacing),
            // 分组之间 20dp：`groupSpacing` 是给"页面上只有两三组"的场景留的，
            // 权限中心这种六组的页面照抄它，卡片之间会空出半屏。
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}
