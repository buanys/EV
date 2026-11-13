package ev.buan.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.PluginCard
import ev.buan.ui.EvEmpty
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvTone

/**
 * 插件管理：已安装的容器，一行一个。
 *
 * 信息结构（一眼层 → 扫视层 → 钻取层）：
 *
 * - **一眼层**：卡片的标题，是用户认得出的名字
 * - **扫视层**：版本 + 状态（停用 / 隔离 / 出错），一行之内答"它现在能不能用"
 * - **钻取层**：权限、存储占用、失败次数——详情页还没做，先不塞进列表
 *
 * 开关直接改运行时，不落本地状态：`onToggle` 走宿主的 setEnabled，
 * 真正的启用状态再由 [cards] 回流，列表永远显示运行时的事实。
 */
@Composable
fun PluginManagerScreen(
    cards: List<PluginCard>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    EvPage(title = "插件", onBack = onBack) {
        if (cards.isEmpty()) {
            // 空态占满剩下的高度再居中：贴着标题的一行字读起来像"下面坏了"。
            EvEmpty(
                "还没有插件。把 jar 放进 /sdcard/EV/mods，再重启 EV。",
                modifier = Modifier.weight(1f),
            )
        } else {
            EvSection(title = "已安装 ${cards.size}", footnote = "改动立即生效，不用重启。") {
                cards.forEach { card ->
                    EvRow(
                        title = card.title,
                        summary = summaryOf(card),
                        tone = if (card.error != null) EvTone.Danger else EvTone.Normal,
                        onClick = { onToggle(card.id, !card.enabled) },
                        trailing = {
                            EvText(
                                if (card.enabled) "已启用" else "已停用",
                                kind = EvTextKind.Caption,
                                tone = if (card.enabled) EvTone.Muted else EvTone.Warning,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 一行话说明它现在什么状态。出错优先于一切：那是用户唯一需要看到的东西。 */
private fun summaryOf(card: PluginCard): String {
    // 先取到局部变量：error 是跨模块的 val，直接 smart cast 不了。
    val error = card.error
    return when {
        error != null -> error
        card.isolatedTools > 0 -> "${card.version} · 隔离运行 ${card.isolatedTools} 个工具"
        card.failureCount > 0 -> "${card.version} · 失败 ${card.failureCount} 次"
        else -> card.version
    }
}
