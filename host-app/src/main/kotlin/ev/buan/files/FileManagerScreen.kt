package ev.buan.files

import android.os.Environment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.text.CouiTextKind
import ev.buan.ui.Chevron
import ev.buan.ui.EvDivider
import ev.buan.ui.EvEmpty
import ev.buan.ui.EvIcon
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTone
import ev.buan.ui.rememberEvScroll
import java.io.File

/** 一次目录读取的结果。读不到必须和"空目录"分开——这是这个页面存在的理由之一。 */
private sealed interface DirState {
    data class Loaded(val dirs: List<File>, val files: List<File>) : DirState
    data class Failed(val reason: String) : DirState
}

/**
 * 文件管理：从内部存储根开始浏览。
 *
 * 它同时也是**权限的体检工具**：这一页用的是 EV 自己的进程，
 * 所以"能不能读 /sdcard"在这里会直接显形——读不到就报原因，而不是像插件扫描那样静默变成空列表。
 *
 * 只读。没有删除、没有移动：一个还在验证投放链路的阶段，给文件操作是给自己挖坑。
 *
 * 上一版的三处毛躁：
 * - 箭头是 `›` 这个字符——它跟着字体走，字号一变粗细就变，和旁边图标不同源；
 * - 每个目录的 summary 都写"目录"——名字已经说明了，这是纯噪音；
 * - 所有行裸排在 LazyColumn 里，没有组、没有分隔线，读起来是一串漂浮的文字。
 */
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val root = remember { Environment.getExternalStorageDirectory().absolutePath }
    var path by remember { mutableStateOf(root) }
    val state = remember(path) { readDir(path) }

    EvPage(
        title = if (path == root) "文件" else File(path).name.ifBlank { "文件" },
        onBack = {
            // 到根再返回，就退出这一页；否则回到上一级。
            if (path == root) onBack() else path = File(path).parent ?: root
        },
        scrollable = false,
    ) {
        // 当前位置 + 上级入口，合成一块：它回答"我在哪、怎么回去"。
        EvSection(title = if (path == root) "内部存储" else "当前位置") {
            EvRow(
                title = if (path == root) "内部存储" else "返回上一级",
                summary = path.removePrefix(root).ifBlank { "/" },
                icon = if (path == root) EvIcon.Folder else EvIcon.Back,
                onClick = { if (path == root) onBack() else path = File(path).parent ?: root },
                trailing = {
                    when (state) {
                        is DirState.Loaded ->
                            EvText(
                                "${state.dirs.size} 目录 · ${state.files.size} 文件",
                                kind = CouiTextKind.PreferenceSummary,
                                tone = EvTone.Muted,
                            )
                        is DirState.Failed -> EvText("读不到", kind = CouiTextKind.PreferenceSummary, tone = EvTone.Danger)
                    }
                },
            )
        }

        when (state) {
            is DirState.Failed -> EvSection(
                title = "读不到",
                footnote = "这一页用的是 EV 自己的进程，所以这里的状态就是它的真实权限。",
            ) {
                EvRow(title = "无法读取目录", summary = state.reason, tone = EvTone.Danger)
            }

            is DirState.Loaded -> {
                if (state.dirs.isEmpty() && state.files.isEmpty()) {
                    EvEmpty("这个目录是空的。")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().then(rememberEvScroll().modifier),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.dirs, key = { "d:${it.absolutePath}" }) { dir ->
                            EvRow(
                                title = dir.name,
                                icon = EvIcon.Folder,
                                onClick = { path = dir.absolutePath },
                                trailing = { Chevron() },
                            )
                            EvDivider(startOffset = 52.dp)
                        }
                        items(state.files, key = { "f:${it.absolutePath}" }) { file ->
                            EvRow(
                                title = file.name,
                                summary = "${humanSize(file.length())} · ${humanTime(file.lastModified())}",
                                tone = EvTone.Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 读一个目录。**读不到要给理由**：null 和空数组必须区分开。 */
private fun readDir(path: String): DirState {
    val dir = File(path)
    if (!dir.exists()) return DirState.Failed("目录不存在")
    if (!dir.isDirectory) return DirState.Failed("不是一个目录")
    // listFiles() 在无权限时返回 null，而不是抛异常——这是最容易吞掉错误的地方。
    val children = dir.listFiles()
        ?: return DirState.Failed("没有读取权限（listFiles 返回 null）")
    val dirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    val files = children.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
    return DirState.Loaded(dirs, files)
}

private fun humanTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
