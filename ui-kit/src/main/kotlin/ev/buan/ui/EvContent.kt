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

/** 语义，不是颜色。 */
enum class EvTone { Normal, Muted, Accent, Warning, Danger, OnAccent }

/**
 * 页面里唯一的文字入口。
 *
 * 直接吃 COUI 的 [CouiTextKind]：字号、字重、字族、行高全部由 COUI 的 15 个文字角色决定，
 * 这里不再有自己的一套七级字阶，也没有"EV 档位 → COUI 角色"的映射表。
 * 那层映射曾经把 15 个角色压成 7 个，结果是 `Category`、`GroupTitle`、`PreferenceValue`、
 * `Toolbar` 这些 COUI 专门为某种位置准备的角色，在 EV 里根本没有机会用到，
 * 同一屏里就会出现"这一行用的是 COUI 的 Body，那一行的标题只能凑一个 PreferenceTitle"。
 *
 * 需要哪一档就问 COUI 要哪一档，不再经过中间层。
 */
@Composable
fun EvText(
    text: String,
    modifier: Modifier = Modifier,
    kind: CouiTextKind = CouiTextKind.Body,
    tone: EvTone = EvTone.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    CouiText(
        text = text,
        modifier = modifier,
        kind = kind,
        color = tone.color(),
        maxLines = maxLines,
    )
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
        title = { EvText(title, kind = CouiTextKind.Body, tone = tone, maxLines = 1) },
        summary = summary?.let { text ->
            { EvText(text, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted, maxLines = 2) }
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
