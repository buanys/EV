package ev.buan.plugins.ev.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.plugins.ev.ui.ui.ellipsize
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvIcons
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * Chrome shared by the chat surface and its second-level pages: the home top bar, the model detail
 * card it opens, and the plain page bar every non-chat page uses.
 *
 * Split out of EvUiPlugin.kt so the plugin class keeps orchestration and these keep rendering.
 */
@Composable
internal fun EvModelDetailCard(
    modelName: String?,
    lastTokens: Int?,
    onSwitchModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    Column(
        modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .clip(EvThemeTokens.radii.largeShape)
            .background(tokens.surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "当前模型",
            style = EvThemeTokens.type.micro.style(tokens.textTertiary),
        )
        Text(
            text = modelName ?: "未接模型",
            style = EvThemeTokens.type.title.style(if (modelName != null) tokens.textPrimary else tokens.textTertiary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "上轮消耗 ${lastTokens?.let { "$it tok" } ?: "—"}",
            style = EvThemeTokens.type.caption.style(tokens.textSecondary),
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(tokens.dividerGhost),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(EvThemeTokens.radii.small))
                .clickable(onClickLabel = "切换模型", onClick = onSwitchModel)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvGlyph(EvGlyphKind.Atom, color = tokens.accent, modifier = Modifier.size(16.dp))
            Text(
                text = "切换模型",
                style = EvThemeTokens.type.label.style(tokens.accent),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The three-segment top bar (spec §5.2): drawer entry on the left, the context capsule in the
 * middle, and one capsule of actions on the right. The capsule reads the current model live;
 * tapping it opens the detail card, and the spend chip that used to sit here moved inside it.
 */
@Composable
internal fun EvHomeTopBar(
    title: String?,
    modelName: String?,
    approvalPending: Boolean,
    onMenu: () -> Unit,
    onCapsule: () -> Unit,
    onNewChat: () -> Unit,
    onMore: () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "菜单", onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Menu, color = tokens.textPrimary, modifier = Modifier.size(20.dp))
            // An approval waiting on the human is the one thing worth a badge here.
            if (approvalPending) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tokens.accent),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // The left half used to be two symmetric weights around the model capsule, which bought
        // 71dp of dead space on either side. That space now says where you are: the conversation
        // you are in. Identity outranks status, so the model drops to a quiet line beside it.
        if (title != null) {
            Text(
                // ~24 display units: enough for a real title, small enough that the model status
                // and the action pill keep their own breathing room on a 425dp bar.
                text = title.ellipsize(24),
                style = EvThemeTokens.type.label.style(tokens.textPrimary),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(12.dp))
        if (modelName != null) {
            Row(
                Modifier
                    .clip(EvThemeTokens.radii.smallShape)
                    .clickable(onClickLabel = "模型状态", onClick = onCapsule)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(tokens.success),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = modelName,
                    style = EvThemeTokens.type.micro.style(tokens.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 84.dp),
                )
            }
        } else {
            Text(
                text = "未接模型",
                style = EvThemeTokens.type.micro.style(tokens.textTertiary),
                modifier = Modifier
                    .clip(EvThemeTokens.radii.smallShape)
                    .clickable(onClickLabel = "模型状态", onClick = onCapsule)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        if (title == null) Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .clip(CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "新会话", onClick = onNewChat),
                contentAlignment = Alignment.Center,
            ) {
                // Both glyphs are secondary now: this pill holds two *conveniences*, not the
                // screen's primary action, and at surface2 it was the heaviest thing on an
                // otherwise empty page.
                EvGlyph(EvGlyphKind.Chat, color = tokens.textSecondary, modifier = Modifier.size(16.dp))
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "更多", onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(EvGlyphKind.More, color = tokens.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Opened as a normal page: the management entry becomes a back arrow. */
@Composable
internal fun EvPageTopBar(title: String, onBack: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clickable(onClickLabel = "返回") { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = EvIcons.ArrowBack,
                contentDescription = "返回",
                tint = tokens.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = EvThemeTokens.type.title.style(tokens.textPrimary),
        )
    }
}
