package ev.buan.settings

import androidx.compose.runtime.Composable
import ev.buan.SlotHost
import org.cordis.api.UiSlot
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvThemeMode
import ev.buan.ui.EvTone

/**
 * 设置。
 *
 * 分三段：**外观**（看见什么）、**行为**（它怎么动）、**关于**（这份是什么）。
 * 每段最多三行——设置页的敌人不是不全面，是找不到。
 *
 * 所有改动写回宿主的外观状态，没有"保存"按钮：改了就生效，
 * 免掉一次"我刚才点的是确定还是取消"。
 */
@Composable
fun SettingsScreen(
    appearance: EvAppearance,
    onThemeMode: (EvThemeMode) -> Unit,
    onPureBlack: (Boolean) -> Unit,
    onBlur: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    EvPage(title = "设置", onBack = onBack) {
        EvSection(title = "外观") {
            ThemeModeRow("跟随系统", EvThemeMode.SYSTEM, appearance, onThemeMode)
            ThemeModeRow("浅色", EvThemeMode.LIGHT, appearance, onThemeMode)
            ThemeModeRow("深色", EvThemeMode.DARK, appearance, onThemeMode)
        }
        EvSection(title = "深色主题", footnote = "只在深色下生效。") {
            EvRow(
                title = "纯黑背景",
                summary = "AMOLED 屏幕上更省电，也更快",
                onClick = { onPureBlack(!appearance.pureBlackEnabled) },
                trailing = { OnOff(appearance.pureBlackEnabled) },
            )
            EvRow(
                title = "玻璃模糊",
                summary = "关掉之后悬浮面变成纯色",
                onClick = { onBlur(!appearance.blurEnabled) },
                trailing = { OnOff(appearance.blurEnabled) },
            )
        }
        SlotHost(UiSlot.SettingsSections)
        EvSection(title = "关于") {
            EvRow(title = "版本", trailing = { Value("0.1.0") })
            EvRow(title = "插件目录", trailing = { Value("应用私有目录") })
        }
    }
}

@Composable
private fun ThemeModeRow(
    label: String,
    mode: EvThemeMode,
    appearance: EvAppearance,
    onThemeMode: (EvThemeMode) -> Unit,
) {
    val selected = appearance.themeMode == mode
    EvRow(
        title = label,
        tone = if (selected) EvTone.Accent else EvTone.Normal,
        onClick = { onThemeMode(mode) },
        trailing = { if (selected) Value("当前") },
    )
}

@Composable
private fun OnOff(on: Boolean) {
    EvText(
        if (on) "开" else "关",
        kind = EvTextKind.Caption,
        tone = if (on) EvTone.Accent else EvTone.Muted,
    )
}

@Composable
private fun Value(text: String) {
    EvText(text, kind = EvTextKind.Caption, tone = EvTone.Muted)
}
