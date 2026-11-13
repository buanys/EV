package ev.buan.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ev.buan.PluginCard
import ev.buan.ui.EvButton
import ev.buan.ui.EvEmpty
import ev.buan.ui.EvNotice
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvTone
import kotlinx.coroutines.launch
import org.cordis.api.PluginInstallResult

/**
 * 插件管理：已装的一行一个，**安装只从这里进**。
 *
 * 外部投放目录已经删掉了（见 `PluginFiles` 的类注释）：用户选文件 → 宿主自己写进私有目录。
 * 这条路上文件属主永远是宿主，不会再出现"读不到"和"没装"看起来一样的情况。
 *
 * 信息结构（一眼层 → 扫视层 → 钻取层）：
 * - 一眼层：卡片标题，是用户认得出的名字
 * - 扫视层：版本 + 状态（停用 / 隔离 / 出错），一行之内答"它现在能不能用"
 * - 钻取层：权限、存储、失败次数——详情页还没做，先不塞进列表
 */
@Composable
fun PluginManagerScreen(
    cards: List<PluginCard>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onInstall: suspend (jar: ByteArray, sidecarJson: String) -> PluginInstallResult,
    onUninstall: suspend (String) -> Unit,
    onRollback: suspend (String) -> Unit,
    canRollback: (String) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 点开的那个插件：删除与回滚是危险动作，不该一直摊在列表里。
    var openId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val read = PickedPlugin.read(context, listOf(uri))) {
            is PickedPlugin.Result.Failed -> message = read.reason
            is PickedPlugin.Result.Ok -> {
                // 覆盖还是新增，看这一份声明的 id 在不在已装列表里。
                val incoming = runCatching {
                    org.json.JSONObject(read.plugin.sidecarJson).optString("id")
                }.getOrNull()
                val replacing = incoming != null && cards.any { it.id == incoming }
                busy = true
                message = null
                scope.launch {
                    val attempt = runCatching { onInstall(read.plugin.jar, read.plugin.sidecarJson) }
                    busy = false
                    val error = attempt.exceptionOrNull()
                    message = if (error != null) {
                        // 不吞异常：一句"没有响应"只会让人反复点，真正的原因必须露出来。
                        "安装失败：${error.message ?: error::class.simpleName}"
                    } else {
                        val result = attempt.getOrNull()
                        when (result?.status?.name) {
                            "INSTALLED" -> if (replacing) "已覆盖安装，旧版留在回滚位" else "已安装"
                            "NEEDS_APPROVAL" -> "已导入，等待确认"
                            null -> "安装失败：宿主没有返回结果"
                            else -> result.message ?: "安装未完成（${result.status.name}）"
                        }
                    }
                }
            }
        }
    }

    EvPage(title = "插件", onBack = onBack) {
        message?.let { text ->
            EvNotice(text, tone = if (text.contains("失败") || text.contains("配错")) EvTone.Danger else EvTone.Muted)
            Spacer(Modifier.height(8.dp))
        }

        EvSection(
            title = "安装",
            footnote = "插件自带元数据：只需要选一个 .jar。",
        ) {
            EvRow(
                title = if (busy) "正在安装…" else "安装插件",
                summary = "从文件里选，宿主会把它复制进自己的目录",
                enabled = !busy,
                onClick = { picker.launch(arrayOf("application/java-archive", "*/*")) },
                trailing = { EvText("选择", kind = EvTextKind.Caption, tone = EvTone.Accent) },
            )
        }

        if (cards.isEmpty()) {
            EvEmpty("还没有插件。")
        } else {
            EvSection(title = "已装 ${cards.size}", footnote = "改动立即生效，不用重启。") {
                cards.forEach { card ->
                    EvRow(
                        title = card.title,
                        summary = summaryOf(card),
                        tone = if (card.error != null) EvTone.Danger else EvTone.Normal,
                        onClick = { openId = if (openId == card.id) null else card.id },
                        trailing = {
                            EvText(
                                if (card.enabled) "已启用" else "已停用",
                                kind = EvTextKind.Caption,
                                tone = if (card.enabled) EvTone.Muted else EvTone.Warning,
                            )
                        },
                    )
                    if (openId == card.id) {
                        EvRow(
                            title = "启用 / 停用",
                            summary = if (card.enabled) "停用它" else "启用它",
                            onClick = {
                                openId = null
                                onToggle(card.id, !card.enabled)
                            },
                            trailing = {
                                EvText(
                                    if (card.enabled) "停用" else "启用",
                                    kind = EvTextKind.Caption,
                                    tone = EvTone.Muted,
                                )
                            },
                        )
                        if (canRollback(card.id)) {
                            EvRow(
                                title = "回滚到上一版",
                                summary = "用覆盖前存下的字节替换当前容器",
                                onClick = {
                                    openId = null
                                    scope.launch {
                                        runCatching { onRollback(card.id) }
                                        message = "已回滚 ${card.title}"
                                    }
                                },
                                trailing = {
                                    EvText("回滚", kind = EvTextKind.Caption, tone = EvTone.Accent)
                                },
                            )
                        }
                        EvRow(
                            title = "卸载插件",
                            summary = "停用并删掉容器；插件自己的数据留着",
                            tone = EvTone.Danger,
                            onClick = {
                                openId = null
                                scope.launch {
                                    runCatching { onUninstall(card.id) }
                                    message = "已卸载 ${card.title}"
                                }
                            },
                            trailing = {
                                EvText("删除", kind = EvTextKind.Caption, tone = EvTone.Danger)
                            },
                        )
                    }
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
