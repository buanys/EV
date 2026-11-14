package ev.buan.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText

/** 文字档位：页面只说"这是什么"，不说"多大"。 */
enum class EvTextKind { Display, Headline, Title, Body, Label, Caption, Micro }

/**
 * 语义，不是颜色。
 *
 * 五个档位是有意的上限：再多，"这个到底该用哪个"就变成每次都要想的问题。
 * 需要更醒目就用 [EvButton]，而不是给文字换颜色。
 */
enum class EvTone { Normal, Muted, Accent, Warning, Danger, OnAccent }

/**
 * 页面里唯一的文字入口。
 *
 * 直接落到 COUI 的 [CouiText]：字号、字重、字族、行高全部由 `CouiTextKind` 决定。
 * 之前这里读的是 EV 自己的一套七级字阶——那套值再讲究，也和 COUI 组件的字不是一个体系，
 * 同一屏里两种字重就会露出来。现在只有一套刻度。
 */
@Composable
fun EvText(
    text: String,
    modifier: Modifier = Modifier,
    kind: EvTextKind = EvTextKind.Body,
    tone: EvTone = EvTone.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    CouiText(
        text = text,
        modifier = modifier,
        kind = kind.toCouiKind(),
        color = tone.color(),
        maxLines = maxLines,
    )
}

/** EV 的七个语义档位 → COUI 自己的文字角色。 */
private fun EvTextKind.toCouiKind(): CouiTextKind = when (this) {
    EvTextKind.Display -> CouiTextKind.HomeTitle
    EvTextKind.Headline -> CouiTextKind.PageTitle
    EvTextKind.Title -> CouiTextKind.SectionTitle
    EvTextKind.Body -> CouiTextKind.Body
    EvTextKind.Label -> CouiTextKind.PreferenceTitle
    EvTextKind.Caption -> CouiTextKind.PreferenceSummary
    EvTextKind.Micro -> CouiTextKind.Description
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
 */
@Composable
fun EvRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    tone: EvTone = EvTone.Normal,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Item(
        modifier = modifier,
        title = { EvText(title, kind = EvTextKind.Body, tone = tone, maxLines = 1) },
        summary = summary?.let { text ->
            { EvText(text, kind = EvTextKind.Caption, tone = EvTone.Muted, maxLines = 2) }
        },
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
    )
}

/** 分隔线：COUI 的 [Divider]，自带两侧缩进，和 [Item] 对齐。 */
@Composable
fun EvDivider(modifier: Modifier = Modifier) {
    Divider(modifier = modifier)
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
