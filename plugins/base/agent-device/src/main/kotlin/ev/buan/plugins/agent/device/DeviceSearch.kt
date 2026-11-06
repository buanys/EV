package ev.buan.plugins.agent.device

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在共享存储里找东西。
 *
 * 为什么要有它：`device_list` 只能一层层列目录，agent 想找"上次那个笔记"就得把整棵树走一遍——
 * 那会烧掉几十轮对话。搜索是"按条件缩小范围"，能一次把答案交出来。
 *
 * 三条硬约束，都是为了防止它把宿主拖死：
 *  - **深度上限**：默认 4 层，最多 8 层；
 *  - **访问条目上限 + 时间预算**：数到 N 个就停，或者超时就停，并如实说"没搜完"；
 *  - **单文件读取上限**：按内容搜时只读前 256 KB，二进制文件直接跳过。
 */
internal class DeviceSearch(
    private val files: DeviceFiles,
    /** 共享存储根，用来把结果表示成相对路径（绝对路径太长，模型用不上）。 */
    private val root: File,
) {

    data class Hit(
        val path: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
        /** 命中内容时的行号（从 1 开始）；按文件名搜时为 0。 */
        val line: Int,
        /** 命中处的一小段上下文，只按内容搜时才有。 */
        val excerpt: String,
    )

    data class Report(
        val hits: List<Hit>,
        val scanned: Int,
        val truncated: Boolean,
        /** 没搜完的原因；搜完了就是 null。 */
        val stopReason: String?,
    )

    /**
     * 搜索。
     *
     * [query] 为空时按 [kind] 列出全部（相当于带过滤的 find）。
     * [kind] = "name" 匹配文件名，= "content" 匹配文件内容，= "both" 两者都算。
     */
    fun search(
        query: String,
        kind: String,
        limit: Int,
        maxDepth: Int,
        includeHidden: Boolean,
    ): Report {
        val hits = mutableListOf<Hit>()
        var scanned = 0
        var truncated = false
        var stopReason: String? = null
        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS

        val base = runCatching { root.canonicalFile }.getOrNull() ?: return Report(emptyList(), 0, false, "拿不到共享存储根")

        // 显式栈而不是递归：目录树是用户控制的，递归深度一旦失控就是 StackOverflow。
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(base to 0)

        while (stack.isNotEmpty()) {
            if (hits.size >= limit) {
                truncated = true
                stopReason = "命中数达到上限 $limit"
                break
            }
            if (scanned >= MAX_SCANNED) {
                truncated = true
                stopReason = "已检查 $MAX_SCANNED 个条目，停止遍历"
                break
            }
            if (System.currentTimeMillis() > deadline) {
                truncated = true
                stopReason = "超过 ${TIME_BUDGET_MS / 1000}s 时间预算，停止遍历"
                break
            }

            val (dir, depth) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue

            for (child in children.sortedBy { it.name }) {
                scanned++
                val name = child.name
                if (!includeHidden && name.startsWith(".")) continue
                // 共享存储的 Android/ 归系统管，翻它既慢又没意义。
                if (name == "Android" && depth == 0) continue

                val relative = child.absolutePath.removePrefix(base.absolutePath + "/")
                val isDir = child.isDirectory

                if (matchName(name, relative, query, kind)) {
                    hits += Hit(relative, name, isDir, if (isDir) 0 else child.length(), 0, "")
                } else if (!isDir && kind != "name" && child.length() <= MAX_CONTENT_BYTES && isTextLikely(child)) {
                    val found = matchContent(child, query)
                    if (found != null) {
                        hits += Hit(relative, name, false, child.length(), found.first, found.second)
                    }
                }

                // 目录入栈（深度预算内）。Android/ 与我们的残留目录不展开。
                if (isDir && depth < maxDepth) stack.addLast(child to depth + 1)
            }
        }

        return Report(hits, scanned, truncated, stopReason)
    }

    private fun matchName(name: String, relative: String, query: String, kind: String): Boolean {
        if (query.isEmpty()) return true
        if (kind == "content") return false
        return name.contains(query, ignoreCase = true) || relative.contains(query, ignoreCase = true)
    }

    /** 返回 (行号, 上下文片段) 或 null。 */
    private fun matchContent(file: File, query: String): Pair<Int, String>? {
        if (query.isEmpty()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        // 二进制文件塞进 String 会出现替换字符，直接不看。
        if (text.contains('\u0000')) return null

        text.lineSequence().forEachIndexed { index, line ->
            if (line.contains(query, ignoreCase = true)) {
                val trimmed = line.trim().take(MAX_EXCERPT)
                return (index + 1) to trimmed
            }
        }
        return null
    }

    /** 便宜的二进制预判：读前 512 字节看有没有 NUL。 */
    private fun isTextLikely(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(512)
            val read = input.read(buffer)
            if (read <= 0) return@runCatching true
            (0 until read).none { buffer[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    fun toJson(report: Report, query: String, kind: String): String = JSONObject()
        .put("query", query)
        .put("kind", kind)
        .put("count", report.hits.size)
        .put("scanned", report.scanned)
        .put("truncated", report.truncated)
        .apply { report.stopReason?.let { put("stop_reason", it) } }
        .put(
            "hits",
            JSONArray().apply {
                report.hits.forEach { hit ->
                    put(
                        JSONObject()
                            .put("path", hit.path)
                            .put("name", hit.name)
                            .put("directory", hit.directory)
                            .put("size", hit.size)
                            .apply {
                                if (hit.line > 0) {
                                    put("line", hit.line)
                                    put("excerpt", hit.excerpt)
                                }
                            },
                    )
                }
            },
        )
        .toString()

    private companion object {
        const val MAX_SCANNED = 20_000
        const val MAX_CONTENT_BYTES = 256 * 1024L
        const val MAX_EXCERPT = 160
        const val TIME_BUDGET_MS = 8_000L
    }
}
