package ev.buan.plugins.ev.ui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * 抽屉的骨架：头部、导航项、折叠组、页脚。
 *
 * 与"抽屉里装什么"分开：这几个组件只认识标签、图标和点击，**不认识会话**。
 * 换掉会话历史那一栏（比如做成搜索优先）时，这一整个文件可以不动。
 */

@Composable
internal fun DrawerHeader(statusLine: String, onSettings: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvLogo()
        Spacer(Modifier.width(12.dp))
        Column {
            Text("EV", style = EvThemeTokens.type.title.style(tokens.textPrimary))
            Text(statusLine, style = EvThemeTokens.type.caption.style(tokens.textSecondary))
        }
        Spacer(Modifier.weight(1f))
        // 设置放在这里，而不是收进「更多工具」：那是唯一一个用户会主动去找、却找不到就会被
        // 判为「这应用没有设置」的入口。新建对话在聊天页顶栏已经有一个，抽屉里不必重复。
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(tokens.surface2)
                .clickable(onClickLabel = "设置", onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Gear, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The mark: four dots in one hue rather than four different colours.
 *
 * In a page whose whole discipline is "one accent, used sparingly" (spec §1.3), a red-blue-yellow-
 * green cluster was the loudest thing on screen and it meant nothing. Same shape, one voice.
 */
@Composable
internal fun EvLogo() {
    val ink = EvThemeTokens.colors.textPrimary
    Box(
        Modifier.size(40.dp).clip(EvThemeTokens.radii.smallShape)
            .background(EvThemeTokens.colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Four equal dots. Mixing alphas made one dot read as a status light and the other
            // three as debris; a uniform 2x2 grid at least reads as a mark.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(ink.copy(alpha = 0.9f))
                Dot(ink.copy(alpha = 0.9f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(ink.copy(alpha = 0.9f))
                Dot(ink.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
internal fun DrawerNav(label: String, glyph: EvGlyphKind, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(glyph, color = tokens.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = EvThemeTokens.type.label.style(tokens.textPrimary))
    }
}

/**
 * The collapsible "更多工具" group.
 *
 * Everything below [DrawerNav]'s three rows is configured once and then forgotten, so it does not
 * deserve the same visual weight as the daily entries (spec §5.1, L2 = 两步可达). Collapsing them
 * also gives the session history its room back: five equal rows pushed the list down for features
 * nobody opens during a conversation.
 *
 * The caret is one glyph rotating 90° rather than two glyphs swapping places, so the shape stays
 * continuous as the group opens (spec §6.2).
 */
@Composable
internal fun DrawerMore(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    Row(
        Modifier.fillMaxWidth()
            .height(40.dp)
            .clickable(
                onClickLabel = if (expanded) "收起更多工具" else "展开更多工具",
                onClick = onToggle,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The caret rides right after the label instead of being pushed to the far edge: parked at
        // the right it lines up with the history timestamps and the row reads as another heading.
        Text(
            text = "更多工具",
            style = EvThemeTokens.type.caption.style(tokens.textSecondary),
        )
        Spacer(Modifier.width(6.dp))
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            label = "moreCaret",
        )
        EvGlyph(
            EvGlyphKind.Chevron,
            color = tokens.textTertiary,
            modifier = Modifier.size(12.dp).rotate(rotation),
        )
        Spacer(Modifier.weight(1f))
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(EvMotionTokens.fadeFast()) + expandVertically(
            EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
        ),
        exit = fadeOut(EvMotionTokens.fadeFast()) + shrinkVertically(
            EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
        ),
    ) {
        Column(content = content)
    }
}

/**
 * A row inside [DrawerMore]: indented under its parent and one step quieter, so the nesting is
 * legible without drawing a guide line (spec §0: depth comes from tone, not from rules).
 */
@Composable
internal fun DrawerSubNav(label: String, glyph: EvGlyphKind, onClick: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(start = 52.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(glyph, color = tokens.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = EvThemeTokens.type.label.style(tokens.textSecondary))
    }
}

/**
 * Footer: the search pill, full width.
 *
 * The gear that used to sit here is gone on purpose - 设置 now lives inside 更多工具, and two
 * entries for one destination is exactly the kind of noise the drawer was reorganised to remove.
 */
@Composable
internal fun DrawerFooter(
    onSearch: () -> Unit,
    showSearch: Boolean,
) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSearch) {
            Row(
                Modifier.weight(1f).height(40.dp).clip(EvThemeTokens.radii.fullShape)
                    .background(tokens.surface2)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvGlyph(EvGlyphKind.Search, color = tokens.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索对话", style = EvThemeTokens.type.caption.style(tokens.textTertiary))
            }
        }
    }
}
