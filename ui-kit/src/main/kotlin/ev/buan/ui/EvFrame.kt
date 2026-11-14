package ev.buan.ui

import android.graphics.BlendMode
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.effect.CouiFloatingMaterial
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.CouiFloatingSurface
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.effect.couiFloatingSurface
import com.itos.coui.floatingactionbutton.CouiFabDefaults
import com.itos.coui.floatingactionbutton.Fab
import com.itos.coui.layout.Scaffold
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.shape.CouiCapsuleShape

/**
 * 页面持有的 backdrop。悬浮件（Fab、菜单、抽屉）靠它采样背后的页面内容。
 *
 * **必须是同一个实例**。上一版把 `Fab` 放进内容区、由它自己去取 backdrop，
 * 结果取到的是 null，直接抛 "FAB 需要显式 backdrop"。共享一个实例是唯一正确的做法。
 */
val LocalEvBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }

/**
 * 页面骨架：唯一的 backdrop + 底色 + 向下提供。
 *
 * 所有页面都从这里开始。它做三件事，而且只做这三件：
 *
 * 1. 记住**一个** backdrop，并把它画到背景层上（`Modifier.couiBackdrop`）
 * 2. 用主题的 surface0 铺底——这一层是材质的采样源，不能透明
 * 3. 把 backdrop 通过 [LocalEvBackdrop] 下发给需要采样的子件
 *
 * 系统栏内边距不在这里做：宿主的入口已经处理过一次，重复处理会出现双倍空白。
 */
@Composable
fun EvPageScaffold(
    modifier: Modifier = Modifier,
    /**
     * 顶部栏位。放 COUI 的 `TopAppBar`，它自己会处理玻璃底、滚动折叠、状态栏内边距。
     *
     * 走 Scaffold 的 topBar 而不是在内容列里画一行：只有栏位知道"内容该从哪开始"，
     * 内容区才能既不压在标题下面、也不多出一段死白。
     */
    topBar: (@Composable () -> Unit)? = null,
    /** 悬浮栏位。**必须走这里**：Scaffold 给它独立的 backdrop 栏位，而不是塞进内容树。 */
    floating: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val colors = EvColors
    // 浮层要采样的 backdrop：内容区（不含浮层）在这里被录制成采样源。
    // 浮层是 Scaffold 的兄弟 placeable，不在这个 Box 的绘制子树里，所以不会采样到自身。
    val backdrop = rememberCouiBackdrop()
    CompositionLocalProvider(LocalEvBackdrop provides backdrop) {
        Scaffold(
            // 让位于键盘：宿主只处理了 systemBars（那是它自己的窗口），
            // IME 归页面管。少了这一行，键盘弹出来会直接盖住输入框。
            modifier = modifier.fillMaxSize().imePadding(),
            topBar = topBar ?: {},
            containerColor = colors.surface0,
            floatingActionButton = floating,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).couiBackdrop(backdrop)) {
                content()
            }
        }
    }
}

/**
 * 图标按钮：40dp 的圆角方块 + COUI 的按压反馈。
 *
 * **不能用 COUI 的 `TopAppBarButton`**：它要求自己处在顶栏的效果作用域里
 * （`CouiTopAppBarButtonDefaults.style` 通过 CompositionLocal 下发），
 * 脱离 `TopAppBar` / `TopAppBarButtonGroup` 单独使用会直接抛
 * "顶栏按钮必须位于顶栏效果作用域内"——这条是实测崩出来的，不是猜的。
 *
 * 所以页面里散落的入口（新建对话、更多操作）走这个自绘版本：
 * 形状用 [CouiShape]、按压形变用 `couiButtonFeedback`，材质与顶栏按钮一致，
 * 只是不参与顶栏的共享过渡。真正在顶栏里的按钮（返回）由 [EvPage] 直接用 COUI 的。
 */
@Composable
fun EvIconButton(
    icon: EvIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = EvColors
    val vector = EvIcon(icon)
    val dark = CouiTheme.isDark
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(40.dp)
            .background(colors.surface2, CouiShape(12.dp))
            .couiButtonFeedback(dark, interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(vector),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (enabled) colors.textPrimary else colors.textTertiary,
            ),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 悬浮动作按钮。
 *
 * COUI 的 [Fab] 是**单主操作**的表达：56dp 固定尺寸、玻璃采样背后的内容。
 * 别把它当"页面角落的图标入口"用——两个 56dp 的材质球并排压在一屏最上层，
 * 视觉重量会盖过内容，而且在没有内容的地方采样只会得到雾。
 * 次要入口请用 [EvIconButton]。
 *
 * 只有拿到页面下发的 backdrop 才用 COUI 的 [Fab]（材质、轮廓光、按压形变都在它里面）；
 * 拿不到，或者系统低于 34（`Fab` 标了 `@RequiresApi(34)`），退回普通圆按钮。
 *
 * **不假装有玻璃**：在没能力渲染材质的版本上硬套，只会得到一块灰。
 */
@Composable
fun EvFab(
    icon: EvIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val vector = EvIcon(icon)
    val colors = EvColors
    // `CouiTheme.isDark` 是 @Composable getter，必须在外层取，不能塞进 remember 的 lambda。
    val dark = CouiTheme.isDark
    // backdrop 由 Scaffold 的悬浮栏位提供（`Fab` 自己从 CompositionLocal 取）。
    // 自己传一个会与页面根节点形成嵌套，RenderNode 递归到栈溢出
    // （tombstone: SEGV_ACCERR / prepareTreeImpl）。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Box(modifier) {
            Fab(
                renderer = remember { CouiFloatingRenderer.standalone() },
                // 默认材质是 accent（饱和蓝）：深色页面上两个高饱和的圆压在最上层，
                // 视线会先落到按钮而不是内容上。底色退到二级浮起层，只让材质
                // （模糊、轮廓光、按压形变）负责"浮起"，颜色不参与抢注意力。
                material = remember(colors, dark) { CouiFabDefaults.material(colors.surface2, dark) },
                contentColor = colors.textPrimary,
                onClick = { if (enabled) onClick() },
            ) {
                Image(
                    painter = rememberVectorPainter(vector),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(
                        if (enabled) colors.textPrimary else colors.textTertiary,
                    ),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    } else {
        Box(
            modifier = modifier
                .size(48.dp)
                .background(colors.surface2, CouiCapsuleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberVectorPainter(vector),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (enabled) colors.textPrimary else colors.textTertiary,
                ),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
