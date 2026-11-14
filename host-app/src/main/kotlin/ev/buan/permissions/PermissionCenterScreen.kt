package ev.buan.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.text.CouiTextKind
import ev.buan.ui.Chevron
import ev.buan.ui.EvColors
import ev.buan.ui.EvDivider
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTone

/**
 * 权限中心：**还差什么**排在最前面，已授予的收成一行。
 *
 * 这一页存在是因为 agent 能不能动手取决于这几项，而失败现场通常在别的 app 里。
 *
 * ## 为什么分成两组而不是一张大卡
 *
 * 十二个权限平铺成一张卡片，每一行长得一样、重量一样——那是数据库，不是页面。
 * 实际上它们分两类：
 *
 * - **必须**：无障碍、所有文件、通知使用权。缺了这几项，EV 基本动不了。
 * - **按需**：相机、麦克风、定位、通讯录……只有对应的工具被调用时才需要。
 *
 * 分开之后，"还差 12 项"这句话才有落点：前三项是红线，剩下的是能力。
 *
 * ## 状态用形状
 *
 * 左边一个 7dp 点：绿＝已授予、琥珀＝还差。文字留给"这一项是什么"，
 * 状态交给一眼可辨的形状——和侧边栏的连接状态、设置页的开关是同一套语言。
 */
@Composable
fun PermissionCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 每次回到前台重读：用户多半是刚授权完返回的。
    var entries by remember { mutableStateOf(PermissionProbe.read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) entries = PermissionProbe.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    EvPage(title = "权限中心", onBack = onBack) {
        val missing = entries.filter { !it.granted }
        val granted = entries.filter { it.granted }
        val core = listOf("accessibility", "all_files", "notification_access")
        val missingCore = missing.filter { it.key in core }
        val missingOptional = missing.filter { it.key !in core }

        // 一句话结论：这一屏最该先读到的不是清单，而是"现在能不能用"。
        EvSection(title = "状态") {
            EvRow(
                title = when {
                    missing.isEmpty() -> "全部就绪"
                    missingCore.isNotEmpty() -> "还差 ${missingCore.size} 项关键权限"
                    else -> "还差 ${missing.size} 项按需权限"
                },
                summary = when {
                    missing.isEmpty() -> "EV 现在能完整地动手。"
                    missingCore.isNotEmpty() -> "缺了这几项，截屏、点按都做不了。"
                    else -> "不影响基本操作，用到对应工具时才需要。"
                },
                trailing = { StatusDot(granted = missing.isEmpty()) },
            )
        }

        if (missingCore.isNotEmpty()) {
            EvSection(
                title = "必须",
                footnote = "点一行去系统设置里打开；回来这一页会自动刷新。",
            ) {
                missingCore.forEachIndexed { index, entry ->
                    if (index > 0) EvDivider(startOffset = 52.dp)
                    PermissionRow(entry, context)
                }
            }
        }

        if (missingOptional.isNotEmpty()) {
            EvSection(title = "按需") {
                missingOptional.forEachIndexed { index, entry ->
                    if (index > 0) EvDivider(startOffset = 52.dp)
                    PermissionRow(entry, context)
                }
            }
        }

        if (granted.isNotEmpty()) {
            // 已授予的收成一行：它们不再是需要行动的东西，
            // 摊开只会把"还差什么"挤出第一屏。
            var expanded by remember { mutableStateOf(false) }
            EvSection(title = "已授予") {
                EvRow(
                    title = "${granted.size} 项已开启",
                    summary = granted.joinToString("、") { it.title },
                    onClick = { expanded = !expanded },
                    trailing = {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            StatusDot(granted = true)
                            androidx.compose.foundation.layout.Spacer(
                                androidx.compose.ui.Modifier.size(12.dp),
                            )
                            Chevron(up = expanded)
                        }
                    },
                )
                if (expanded) {
                    granted.forEach { entry ->
                        EvDivider(startOffset = 52.dp)
                        PermissionRow(entry, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(entry: PermissionEntry, context: android.content.Context) {
    EvRow(
        title = entry.title,
        summary = entry.summary,
        onClick = entry.target?.let { target -> { context.startActivity(PermissionProbe.intent(context, target)) } },
        // 行里不再放状态点。
        //
        // 十二行全是"未授予"，十二个一模一样的琥珀点在右边排成一列——
        // 它们不回答任何问题（用户扫一眼分组标题"还差 2 项"就知道了），
        // 只是把右侧挤成"点 + 箭头"两个符号并排。
        //
        // 状态只出现在**需要它的两处**：顶部的总结行（好/差一个点），
        // 和"已授予"这一组的标题。行本身只回答"点进去能做什么"，所以只有箭头。
        trailing = { if (!entry.granted) Chevron() else Unit },
    )
}

/** 权限 → 图标。COUI 的图标集有限，用语义最近的：不为了凑形状自己画一套。 */
/**
 * 状态点：7dp 的圆点，已授予用成功色、缺失用警告色。
 *
 * 不用文字（"已开启"/"去开启"）有两个原因：一是它把状态和动作混在一个位置
 * （"去开启"其实是动词，读起来像按钮标题）；二是中文两三个字宽度不一，
 * 一列排下来右边参差不齐，而点永远是同一个尺寸。
 */
@Composable
private fun StatusDot(granted: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .background(
                if (granted) EvColors.success else EvColors.warning,
                CouiCapsuleShape,
            ),
    )
}
