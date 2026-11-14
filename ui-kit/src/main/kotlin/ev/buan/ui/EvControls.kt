package ev.buan.ui

import com.itos.coui.text.CouiTextKind

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.Footer
import com.itos.coui.theme.CouiTheme
import com.itos.coui.shape.CouiShape
import com.itos.coui.shape.CouiCapsuleShape

/**
 * 按钮的三种身份。
 *
 * 只有三种：主操作、次操作、破坏性。多一档就多一次"这两个有什么区别"的犹豫。
 */
enum class EvButtonKind { Primary, Secondary, Danger }

/**
 * 按钮尺寸。Compact 只用于**已经在容器里**的按钮（输入区工具栏、行内操作），
 * 这时候 42dp 会把整行撑高，视觉上比它承载的动作重要得多。
 */
enum class EvButtonSize { Regular, Compact }

/**
 * 页面里唯一的按钮。
 *
 * 按压反馈来自 COUI 的 [couiButtonFeedback]——那是它材质的一部分，
 * 自己画一个 `Box + clickable` 拿不到那种"按下时向内收"的形变。
 */
@Composable
fun EvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: EvButtonKind = EvButtonKind.Primary,
    enabled: Boolean = true,
    size: EvButtonSize = EvButtonSize.Regular,
) {
    val colors = EvColors
    val dark = CouiTheme.isDark
    val interaction = remember { MutableInteractionSource() }
    val compact = size == EvButtonSize.Compact
    val height = if (compact) 32.dp else 42.dp
    val background = when {
        !enabled -> colors.surface3
        kind == EvButtonKind.Primary -> colors.accent
        kind == EvButtonKind.Danger -> colors.danger
        else -> colors.surface2
    }
    val tone = when {
        !enabled -> EvTone.Muted
        kind == EvButtonKind.Secondary -> EvTone.Normal
        else -> EvTone.OnAccent
    }

    Box(
        modifier = modifier
            .height(height)
            .background(background, CouiShape(height / 2))
            .then(Modifier.couiButtonFeedback(dark, interaction, enabled))
            .clickable(interaction, null, enabled, onClick = onClick)
            .padding(horizontal = if (compact) 13.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        EvText(
            label,
            kind = if (compact) CouiTextKind.PreferenceSummary else CouiTextKind.PreferenceTitle,
            tone = tone,
            maxLines = 1,
        )
    }
}

/**
 * 实心圆形图标按钮：能执行时是强调色实心，不能执行时退到二级表面。
 *
 * 和 [EvIconButton] 的区别在**语气**：这个表示"按下去会立刻发生一件事"（发送、确认），
 * 所以用实心 + 反色图标；[EvIconButton] 是入口（更多、新建），所以是玻璃底色。
 *
 * 形状用真圆（`CouiCapsuleShape`）而不是 11dp 圆角：它旁边就是一个胶囊输入框，
 * 一个是"圆头"一个是"圆角方块"，并排时两条轮廓的曲率对不上，看起来就是不齐。
 */
@Composable
fun EvFilledIconButton(
    icon: EvIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = EvColors
    val vector = EvIcon(icon)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(36.dp)
            .background(
                when {
                    !enabled -> colors.surface3
                    pressed -> colors.accent.copy(alpha = 0.82f)
                    else -> colors.accent
                },
                CouiCapsuleShape,
            )
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
            colorFilter = ColorFilter.tint(if (enabled) colors.onAccent else colors.textTertiary),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 空状态：**一行话**，不是一张提示卡片。
 *
 * 参考图里那种带图、带建议的提示块被明确否掉了——空列表时用户需要的是一句说明，
 * 不是一段营销。
 */
@Composable
fun EvEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        EvText(text, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
    }
}

/**
 * 一行提示：错误、警告、被截断的原因。
 *
 * 用 COUI 的 [Footer]：它就是这个位置的正解——附在分组下面的一句小字，
 * 自带左侧缩进（和 [Item] 的标题对齐）、自带的字号与行高、自带的弱化色。
 *
 * 之前是自己画一块 `surface1` 底 + 12dp 圆角的色块，问题有两个：
 * 一是它和上面的分组形成"卡中卡"，两代圆角嵌套，边缘看起来是毛的；
 * 二是提示只是一句话，画成卡片反而把它抬到和内容同等的高度。
 * 颜色仍然由 [tone] 决定——错误必须比脚注更红，这一点 COUI 留给调用方。
 */
@Composable
fun EvNotice(text: String, modifier: Modifier = Modifier, tone: EvTone = EvTone.Muted) {
    Footer(modifier.fillMaxWidth(), text, tone.color())
}

/**
 * 行的"可以去哪里"提示。
 *
 * COUI 的图标集里没有 chevron（`Outlined` 只有 18 个图标，`Filled` 里也没有箭头），
 * 所以用 `Back` 转 180° 画一个——形状和 COUI 的返回箭头同一笔画，只是方向相反。
 * 用文字 `›` 当箭头不行：它跟着字体走，字号一变粗细就变，和旁边的图标不同源。
 */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    val color = EvColors.textTertiary
    androidx.compose.foundation.Canvas(modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = 1.5.dp.toPx()
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            },
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}
