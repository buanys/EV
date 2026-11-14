package ev.buan.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.itos.coui.radiobutton.RadioButton
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import ev.buan.SlotHost
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvDivider
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvThemeMode
import ev.buan.ui.EvTone
import org.cordis.api.UiSlot

/**
 * 设置。
 *
 * 分三段：**外观**（看见什么）、**行为**（它怎么动）、**关于**（这份是什么）。
 * 每段最多三行——设置页的敌人不是不全面，是找不到。
 *
 * 所有改动写回宿主的外观状态，没有"保存"按钮：改了就生效，
 * 免掉一次"我刚才点的是确定还是取消"。
 *
 * 状态的表达一律用 COUI 的控件形状，不用文字：
 * - 二选一的开关 → [Switch]（滑块位置就是答案）
 * - 三选一的主题 → [RadioButton]（圆点位置就是答案）
 *
 * 之前这里是"开/关"和"当前"两个字贴在右边，读一列文字才能知道现在是什么状态；
 * 形状是扫一眼就懂的，而且它自带过渡——点下去滑块会走过去，不是文字瞬间换掉。
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
            EvDivider()
            ThemeModeRow("浅色", EvThemeMode.LIGHT, appearance, onThemeMode)
            EvDivider()
            ThemeModeRow("深色", EvThemeMode.DARK, appearance, onThemeMode)
        }
        EvSection(title = "深色主题", footnote = "只在深色下生效。") {
            SwitchRow(
                title = "纯黑背景",
                summary = "AMOLED 屏幕上更省电，也更快",
                checked = appearance.pureBlackEnabled,
                onCheckedChange = onPureBlack,
            )
            EvDivider()
            SwitchRow(
                title = "玻璃模糊",
                summary = "关掉之后悬浮面变成纯色",
                checked = appearance.blurEnabled,
                onCheckedChange = onBlur,
            )
        }
        SlotHost(UiSlot.SettingsSections)
        EvSection(title = "关于") {
            EvRow(title = "版本", trailing = { Value("0.1.0") })
            EvRow(title = "插件目录", trailing = { Value("应用私有目录") })
        }
    }
}

/** 一行开关：开关本身负责交互，整行也点得动（手指落在文字上时同样生效）。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    EvRow(
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                Modifier,
                checked,
                { onCheckedChange(it) },
            )
        },
    )
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
        onClick = { onThemeMode(mode) },
        // 选中态由圆点表达，不再在右侧写"当前"两个字。
        trailing = { RadioButton(Modifier, selected) },
    )
}

@Composable
private fun Value(text: String) {
    EvText(text, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
}
