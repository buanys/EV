package ev.buan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.hostcore.storage.PluginStorageFiles
import ev.buan.ui.EvThemeTokens
import ev.buan.ui.StatusError
import ev.buan.ui.StatusWarning
import org.cordis.api.HostPermission
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一张插件卡：名字和版本在上、开关在右、一行动作在下，权限与占用折叠在后面。
 *
 * 卡片是管理页里唯一"每个插件都长一遍"的东西，所以折叠与展开归它自己管；
 * 页面只负责给数据、接回调。
 */

/**
 * One plugin as a KernelSU-style module card: name and version on top, the switch on the right,
 * one action row underneath ("打开" / "卸载"), and the permission + storage detail folded away
 * until the card is tapped.
 *
 * Uninstall is a two-step confirm rendered inside the card rather than a dialog: the card is
 * already the thing being acted on, so asking next to it keeps the target and the question in one
 * place and avoids pulling a dialog API into a page that has no other modal.
 */
@Composable
internal fun PluginSection(
    card: PluginCard,
    confirming: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onPermission: (String, HostPermission, Boolean) -> Unit,
    onClearStorage: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onConfirmUninstall: () -> Unit,
    onCancelUninstall: () -> Unit,
    onTrustUpdates: (String, Boolean) -> Unit,
    onPinShortcut: (String, ShortcutDeclaration) -> Unit,
) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            // surface0 is the page background; the card needs the next layer up. Miuix surface is
            // forced to pure black in this theme, which made every card invisible.
            .background(EvThemeTokens.colors.surface1)
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title.ifBlank { card.id },
                    style = MiuixTheme.textStyles.subtitle,
                    color = colors.onBackground,
                )
                Text(
                    text = buildString {
                        append("版本 ").append(card.version)
                        append("  ·  api ").append(card.apiVersion)
                        if (!card.signed) append("  ·  未签名")
                        // 一眼层只加一个词：开了才显示，没开不制造噪音。
                        if (card.trustsUpdates) append("  ·  自动信任")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (card.body.isNotBlank()) {
                    Text(
                        text = card.body,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

            }
            Box(Modifier.padding(start = 12.dp)) {
                Switch(checked = card.enabled, onCheckedChange = { onToggle(card.id, it) })
            }
        }

        if (expanded) {
            val requested = HostPermission.entries.filter { it in card.requestedPermissions }
            if (requested.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "权限",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                requested.forEach { permission ->
                    val granted = permission in card.grantedPermissions
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onPermission(card.id, permission, !granted)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = permissionLabel(permission),
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (granted) "已授予" else "未授予",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (granted) colors.primary else StatusWarning,
                        )
                    }
                }
            }
            // 桌面入口：插件声明了才有这一节。点了会弹系统确认框，不是静默创建。
            if (card.shortcuts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "桌面快捷方式",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                card.shortcuts.forEach { shortcut ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "添加 ${shortcut.label} 到桌面") {
                                onPinShortcut(card.id, shortcut)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = shortcut.label,
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "添加到桌面",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.primary,
                        )
                    }
                }
            }

            // 未签名插件才有「信任后续版本」：已签名走签名链，不需要人逐次点头。
            // 放在展开区，因为它是一次性的决定，不是每次打开都要看的开关。
            if (!card.signed) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "信任后续版本",
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onBackground,
                        )
                        Text(
                            text = if (card.trustsUpdates) {
                                "权限与依赖不变时的更新会自动通过"
                            } else {
                                "每次更新都需要你确认"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = card.trustsUpdates,
                        onCheckedChange = { onTrustUpdates(card.id, it) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "存储 ${formatBytes(card.storageBytes)} / ${formatBytes(PluginStorageFiles.DEFAULT_QUOTA_BYTES)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            // 跨模块的 public 属性不能直接 smart cast（Kotlin 只看得到类型，不保证稳定），
            // 先绑到局部变量：`card` 现在来自 :host-runtime。
            val cardError = card.error
            if (cardError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = cardError,
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusError,
                )
            } else if (!card.enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已禁用，不会加载",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )

            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A plugin without a UI page still offers "打开" is pointless, so the action only
            // appears when there is somewhere to go.
            if (card.ui != null) {
                CardAction(label = "打开") { onOpenPage(card.id) }
                Spacer(Modifier.width(10.dp))
            }
            CardAction(label = "清理") { onClearStorage(card.id) }
            Spacer(Modifier.weight(1f))
            CardAction(label = "卸载", danger = true) { onUninstall(card.id) }
        }

        if (confirming) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "卸载只删除 EV/mods 里的容器文件，插件存储的数据会保留。重启 EV 后生效。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardAction(label = "取消") { onCancelUninstall() }
                Spacer(Modifier.weight(1f))
                CardAction(label = "确认卸载", danger = true) { onConfirmUninstall() }
            }
        }    }
}

@Composable
internal fun CardAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, colors.onSurfaceVariantSummary.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (danger) StatusError else colors.primary,
        )
    }
}
