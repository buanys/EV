package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * 抽屉里的会话历史：一条记录、搜索框、删除确认。
 *
 * 与骨架分开的原因是它们都在处理同一件事——**一份可删的列表**：搜索在过滤它、
 * 条目在表示它、对话框在确认删除它。
 */

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HistoryItem(
    session: EvSessionSummary,
    onSelect: (String) -> Unit,
    onRequestDelete: () -> Unit = {},
) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(EvThemeTokens.radii.smallShape)
            .background(if (session.selected) tokens.accent.copy(alpha = 0.12f) else Color.Transparent)
            // 长按删除：列表是单列文字，塞不下删除按钮；长按是列表删除的通用手势。
            .combinedClickable(
                onClick = { onSelect(session.id) },
                onLongClick = onRequestDelete,
            )
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The 3dp accent bar marks the current session (spec §7.4).
        Box(
            Modifier.width(3.dp).height(16.dp)
                .clip(CircleShape)
                .background(if (session.selected) tokens.accent else Color.Transparent),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = session.title.ellipsize(24),
            style = EvThemeTokens.type.label.style(tokens.textPrimary),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (session.timeLabel.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.timeLabel,
                style = EvThemeTokens.type.micro.style(tokens.textTertiary),
            )
        }
    }
}

@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(40.dp)
            .clip(EvThemeTokens.radii.fullShape)
            .background(tokens.surface2)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(EvGlyphKind.Search, color = tokens.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = EvThemeTokens.type.label.style(tokens.textPrimary),
            cursorBrush = SolidColor(tokens.accent),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "搜索对话",
                            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            "取消",
            style = EvThemeTokens.type.caption.style(tokens.accent),
            modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 删除会话前的确认。会话是用户唯一不可重建的东西——一旦删掉，里面的对话就没了，
 * 所以这里必须显式点一次「删除」，不做滑动即删。
 */
@Composable
internal fun DeleteSessionDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            // 点遮罩等于取消：删除不是需要"挡住误触"的危险操作，它本身还有二次确认。
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(EvThemeTokens.radii.largeShape)
                .background(tokens.surface2)
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = "删除会话",
                style = EvThemeTokens.type.title.style(tokens.textPrimary),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "「" + title.ellipsize(28) + "」及其中全部对话将被删除，无法恢复。",
                style = EvThemeTokens.type.caption.style(tokens.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DialogAction("取消", tokens.textSecondary, tokens.surface1, Modifier.weight(1f), onDismiss)
                DialogAction("删除", tokens.danger, tokens.surface1, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
internal fun DialogAction(
    label: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(EvThemeTokens.radii.smallShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = EvThemeTokens.type.label.style(color),
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}
