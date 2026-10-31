package ev.buan.plugins.agent.browse

import java.io.File
import java.io.IOException

/**
 * 把抓回来的正文写进手机共享存储。
 *
 * 为什么这里有一份自己的实现而不是复用 `agent-device` 的 `device_write`：
 * 插件之间不互相调用工具（那会把"有什么工具"变成运行时依赖），而 `DeviceFiles` 是
 * `agent-device` 的 internal 类，跨模块引用不到。所以这里保留最小的一份：**只写、不读不删**，
 * 边界规则与那边保持一致（共享存储根之内，`Android/` 子树拒写）。
 *
 * 重复的代价被刻意压到很小：这里只有"校验路径 + 写"两件事，读/列/删/移动全不在这里。
 */
internal object SharedSink {

    /** 单次落盘上限：再大的东西应该让用户用别的工具，而不是把正文塞进一次工具调用。 */
    const val MAX_BYTES = 4 * 1024 * 1024

    data class Saved(val path: String, val bytes: Long)

    /**
     * 写入 [text]，返回落盘位置。
     *
     * [overwrite] 为 false 时遇到已存在的文件直接失败——抓取是随时可能重跑的，
     * 默认覆盖会把用户手改过的笔记冲掉。
     */
    fun write(text: String, savePath: String, overwrite: Boolean): Result<Saved> {
        val root = android.os.Environment.getExternalStorageDirectory()
            ?: return Result.failure(IOException("拿不到共享存储根目录"))

        // 相对路径按共享存储根解析；绝对路径照用，但同样要过边界检查。
        val raw = if (savePath.startsWith("/")) File(savePath) else File(root, savePath)
        val canonical = runCatching { raw.canonicalFile }.getOrNull()
            ?: return Result.failure(IOException("非法路径：$savePath"))
        val base = runCatching { root.canonicalFile }.getOrNull()
            ?: return Result.failure(IOException("拿不到共享存储根目录"))

        if (canonical != base && !canonical.path.startsWith(base.path + "/")) {
            return Result.failure(IOException("路径越界：$savePath 不在共享存储内"))
        }

        // 与 device_write 同一条规则：系统托管的子树不碰。
        val android = File(base, "Android")
        if (canonical == android || canonical.path.startsWith(android.path + "/")) {
            return Result.failure(IOException("拒绝写入 ${canonical.absolutePath}：共享存储的 Android/ 子树由系统管理"))
        }

        if (canonical.isDirectory) {
            return Result.failure(IOException("是目录不是文件：${canonical.absolutePath}"))
        }
        if (canonical.exists() && !overwrite) {
            return Result.failure(
                IOException("${canonical.absolutePath} 已存在。要覆盖就传 save_overwrite=true，或换一个路径"),
            )
        }

        val bytes = text.toByteArray()
        if (bytes.size > MAX_BYTES) {
            return Result.failure(
                IOException("正文 ${bytes.size} 字节，超过落盘上限 $MAX_BYTES；用 max_chars 缩小，或分段抓取"),
            )
        }

        val parent = canonical.parentFile
            ?: return Result.failure(IOException("无法确定父目录：${canonical.absolutePath}"))
        if (!parent.isDirectory && !parent.mkdirs()) {
            return Result.failure(IOException("无法创建父目录：${parent.absolutePath}"))
        }

        return runCatching {
            // 先写临时文件再 rename：读者不会看到半截内容。
            val staging = File(parent, ".${canonical.name}.fetching")
            staging.writeBytes(bytes)
            if (!staging.renameTo(canonical)) {
                staging.delete()
                canonical.writeBytes(bytes)
            }
            Result.success(Saved(canonical.absolutePath, canonical.length()))
        }.getOrElse { error ->
            Result.failure(IOException("写入失败：${error.message ?: error.javaClass.simpleName}"))
        }
    }
}
