package ev.buan.ui


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 输入区：一个胶囊。空的时候 52dp，输入到多行时长高，最多 5 行。
 *
 * ```
 * ┌──────────────────────────────────┐
 * │ 说点什么                    [发送] │
 * └──────────────────────────────────┘
 * ```
 *
 * 文本和发送按钮**共用一条垂直中心线**，这是唯一不会看起来"歪了"的排法。
 * 早先的版本把文本区写死 44dp、按钮底对齐，两者各自贴自己的边，中间空出一截，
 * 整块像塌掉的面板。
 *
 * 不再追求"固定高度"：跳动只发生在用户自己打字的时候，为此换来一块永远空着的
 * 两行区域并不划算。
 */
@Composable
fun EvComposer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "说点什么",
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = EvColors
    // 空输入永远保持紧凑；只有真实的换行内容才需要展开空间。
    val expanded = value.contains('\n')
    val targetHeight = when {
        expanded -> 96.dp
        else -> 56.dp
    }
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = EvMotionTokens.gentle<Dp>(),
        label = "composer-height",
    )
    val animatedRadius by animateDpAsState(
        targetValue = if (expanded) 30.dp else 26.dp,
        animationSpec = EvMotionTokens.snappy<Dp>(),
        label = "composer-radius",
    )

    // 占位文字和输入文字必须同一套字形：字号或行高差一点，聚焦的那一刻就会跳一下。
    val inputStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = colors.textPrimary,
    )
    val body: @Composable () -> Unit = {
        Column(
            modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .background(colors.surface1, RoundedCornerShape(animatedRadius))
                // 1dp 的强调色内描边：纯黑底上，没有它输入框只是一个灰块，
                // 有它才读得出"这里可以写字"。
                .border(
                    1.dp,
                    colors.accent.copy(alpha = 0.16f),
                    RoundedCornerShape(animatedRadius),
                )
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = inputStyle,
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 3,
                        decorationBox = { innerTextField ->
                            if (value.isEmpty()) {
                                BasicText(
                                    text = placeholder,
                                    style = inputStyle.copy(color = colors.textTertiary),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
                trailing()
            }
        }
    }

    body()
}

/**
 * 一次工具调用的折叠块。
 *
 * 默认收起，只占一行：状态点 + 标题 + 一行摘要。一次任务动辄十几次调用，
 * 摊开就是几百行输出，"它到底做到哪了"会被淹掉；**等高**才能扫读。
 *
 * 箭头做 180° 旋转而不是换图标：换图标会在两帧之间闪出半个箭头。
 */
@Composable
fun EvCollapsible(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    tone: EvTone = EvTone.Muted,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = EvColors
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    // 形变走空间弹簧（snappy 档）：折叠块是组件级尺寸，用 large-surface 的档会拖沓。
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = EvMotionTokens.reducedAware(!ValueAnimator.areAnimatorsEnabled(), EvMotionTokens::snappy),
        label = "chevron",
    )

    Column(modifier.fillMaxWidth().background(colors.surface1, RoundedCornerShape(18.dp))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(tone.color(), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                EvText(title, kind = EvTextKind.Label, tone = EvTone.Normal, maxLines = 1)
                if (!summary.isNullOrBlank()) {
                    EvText(summary, kind = EvTextKind.Micro, tone = EvTone.Muted, maxLines = 1)
                }
            }
            Image(
                painter = rememberVectorPainter(EvIcon(EvIcon.Back)),
                contentDescription = if (expanded) "收起" else "展开",
                colorFilter = ColorFilter.tint(colors.textTertiary),
                modifier = Modifier.size(16.dp).rotate(-90f + rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            // 尺寸用空间弹簧，透明度用效果的（临界阻尼），两者不混：
            // 透明度过冲看起来就是坏的。
            enter = expandVertically(
                animationSpec = EvMotionTokens.reducedAware(!ValueAnimator.areAnimatorsEnabled(), EvMotionTokens::snappy),
            ) + fadeIn(animationSpec = EvMotionTokens.fadeFast()),
            exit = shrinkVertically(
                animationSpec = EvMotionTokens.reducedAware(!ValueAnimator.areAnimatorsEnabled(), EvMotionTokens::gentle),
            ) + fadeOut(animationSpec = EvMotionTokens.fadeFast()),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * 侧面页（汉堡键的去处）。
 *
 * **不是全屏弹窗**：宽 336dp，背后留一条能看见内容的缝。关掉它是"收起来"，
 * 不是"退出一层"——这个区别决定了用户还记不记得自己刚才在干什么。
 */
@Composable
fun EvSidePage(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = EvColors
    // 面板走空间弹簧（大面元素用 gentle），遮罩只动 alpha。
    // 之前是 `if (!visible) return`：直接出现、直接消失，像一块被贴上来的板子。
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = EvMotionTokens.gentle(),
        label = "side-panel",
    )
    if (!visible && progress == 0f) return

    val panelWidth = 280.dp
    val offsetPx = with(LocalDensity.current) { panelWidth.roundToPx() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // 深色页面上遮罩太轻等于没有：侧页和正文会糊成一片。
                .background(Color.Black.copy(alpha = 0.6f * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = visible,
                    onClick = onDismiss,
                ),
        )
        // 抽屉形态：贴着左边、撑满高度、**只有外侧圆角**。四角圆角的悬浮卡片
        // 在深色页面上读起来像"没做好的弹窗"，而抽屉的直角边本身就是"我从边上出来"的语义。
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .graphicsLayer { translationX = -offsetPx * (1f - progress) }
                .background(
                    colors.surface1,
                    RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                )
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
}
