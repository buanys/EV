package ev.buan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.itos.coui.layout.CouiPageDefaults
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.itos.coui.text.rememberCouiTextStyle
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText

/** 语义，不是颜色。 */
enum class EvTone { Normal, Muted, Accent, Warning, Danger, OnAccent }

/**
 * 页面里唯一的文字入口。
 *
 * 直接吃 COUI 的 [CouiTextKind]：字号、字族、行高全部由 COUI 的 15 个文字角色决定。
 *
 * [weight] 可以**只覆盖字重**，不动字号和字族。这一点很重要：
 * 同一套字阶里，标题和正文如果只差字号、不差粗细，整页就会"平"——
 * 那不是排版，是字号列表。COUI 的每个角色自带一个字重，但首页的主句、
 * 卡片标题这类"需要被先读到"的位置，值得再重半档。
 */
@Composable
fun EvText(
    text: String,
    modifier: Modifier = Modifier,
    kind: CouiTextKind = CouiTextKind.Body,
    tone: EvTone = EvTone.Normal,
    maxLines: Int = Int.MAX_VALUE,
    weight: FontWeight? = null,
) {
    val color = tone.color()
    if (weight == null) {
        CouiText(
            text = text,
            modifier = modifier,
            kind = kind,
            color = color,
            maxLines = maxLines,
        )
    } else {
        // 取 COUI 这个角色的完整 TextStyle（含字族/字距/行高），只换字重。
        BasicText(
            text = text,
            modifier = modifier,
            style = rememberCouiTextStyle(kind).copy(fontWeight = weight, color = color),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一组行：COUI 的 [Group]。
 *
 * 卡片底色、超椭圆圆角、标题与脚注的排布、行间分隔的缩进都在 COUI 里，
 * 页面与这个文件都不重新发明。之前自绘过一次，结果是"圆角、行高、按压反馈"
 * 三处都和旁边的 COUI 组件对不上。
 */
@Composable
fun EvSection(
    title: String? = null,
    footnote: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Group(
        modifier = modifier.fillMaxWidth(),
        title = title,
        footer = footnote,
        content = content,
    )
}

/**
 * 一行：COUI 的 [Item]。
 *
 * 行高、图标尺寸、摘要间距、按压反馈（`couiButtonFeedback(list = true)`）都来自 COUI。
 * `tone` 只影响标题颜色——摘要与右侧内容各有各的语义。
 *
 * [icon] 给左边一个 24dp 的图标位。传了它就是"带图标的设置行"，不传就是纯文字行；
 * COUI 会自己算图标的槽宽和标题的起笔位置，调用方不用再自己量。
 */
@Composable
fun EvRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: EvIcon? = null,
    tone: EvTone = EvTone.Normal,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Item(
        modifier = modifier,
        icon = icon?.let { symbol ->
            {
                Image(
                    painter = rememberVectorPainter(EvIcon(symbol)),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(EvColors.textPrimary.copy(alpha = 0.86f)),
                    modifier = Modifier.size(CouiPageDefaults.iconSize),
                )
            }
        },
        title = { EvText(title, kind = CouiTextKind.Body, tone = tone, maxLines = 1) },
        summary = summary?.let { text ->
            { EvText(text, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted, maxLines = 2) }
        },
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
    )
}

/**
 * 分隔线：COUI 的 [Divider]。
 *
 * [startOffset] 让它从**文字起笔处**开始，而不是从行首（图标脚下）切一刀。
 * iOS 的设置页就是这么做的：线跟着文字走，图标那一列保持连通——
 * 线从图标底下穿过去，一行会被切成"图标 + 内容"两段，读起来像两个元素。
 */
@Composable
fun EvDivider(
    modifier: Modifier = Modifier,
    startOffset: Dp = 0.dp,
    endOffset: Dp = 0.dp,
) {
    // COUI 的 Divider 只有位置参数：modifier、起点缩进、终点缩进、颜色。
    Divider(modifier, startOffset, endOffset)
}

/** 语义 → 颜色。集中在一处，新增语义只改这里。 */
@Composable
internal fun EvTone.color(): Color {
    val colors = EvColors
    return when (this) {
        EvTone.Normal -> colors.textPrimary
        EvTone.Muted -> colors.textTertiary
        EvTone.Accent -> colors.accent
        EvTone.Warning -> colors.warning
        EvTone.Danger -> colors.danger
        EvTone.OnAccent -> colors.onAccent
    }
}
