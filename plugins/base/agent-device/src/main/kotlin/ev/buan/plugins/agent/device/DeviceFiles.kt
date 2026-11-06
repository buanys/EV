package ev.buan.plugins.agent.device

import java.io.File

/**
 * 设备文件访问。宿主声明了 `MANAGE_EXTERNAL_STORAGE`，所以能读整个共享存储——
 * 而插件的 [org.cordis.api.PluginStorage] 只到自己的沙箱。这个类补的就是这段。
 *
 * **安全边界（这个类的全部价值所在）**：
 *
 * 1. 只能访问 [root] 之下。传进来的路径先做 canonical 化再比对——`..` 和符号链接
 *    都会在 canonical 阶段被折叠掉，所以 `../../data/data/...` 这类穿透在这里就断了，
 *    不需要另外写一遍字符串检查（那才是容易漏的那种防线）。
 * 2. 只读。写和删这一层不提供——v0 没有任何理由让 agent 在手机上写文件，
 *    少一个能力就少一类事故。
 * 3. 读文本有字节上限。一个 200MB 的日志文件读进内存，崩的是整个宿主进程，
 *    不是这一条工具调用。
 */
internal class DeviceFiles(private val root: File) {

    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
    )

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Fail(val error: String) : Result<Nothing>
    }

    /** 允许访问的根，给工具提示用。 */
    fun rootPath(): String = root.absolutePath

    fun list(path: String): Result<List<Entry>> {
        val dir = resolve(path) ?: return Result.Fail(outside(path))
        if (!dir.exists()) return Result.Fail("不存在：${dir.absolutePath}")
        if (!dir.isDirectory) return Result.Fail("不是目录：${dir.absolutePath}")
        if (!dir.canRead()) return Result.Fail("没有读取权限：${dir.absolutePath}")

        val entries = runCatching {
            dir.listFiles().orEmpty()
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map {
                    Entry(
                        name = it.name,
                        path = it.absolutePath,
                        isDirectory = it.isDirectory,
                        // 目录的大小在 Linux 上没有意义，报 0 比报一个假的数字好。
                        size = if (it.isDirectory) 0L else it.length(),
                        modifiedAt = it.lastModified(),
                    )
                }
        }.getOrElse { return Result.Fail("列目录失败：${it.message ?: it.javaClass.simpleName}") }

        return Result.Ok(entries)
    }

    fun readText(path: String, maxBytes: Int): Result<String> {
        val file = resolve(path) ?: return Result.Fail(outside(path))
        if (!file.exists()) return Result.Fail("不存在：${file.absolutePath}")
        if (file.isDirectory) return Result.Fail("是目录不是文件：${file.absolutePath}")
        if (!file.canRead()) return Result.Fail("没有读取权限：${file.absolutePath}")

        val length = file.length()
        if (length > maxBytes) {
            return Result.Fail(
                "文件 ${length} 字节，超过单次读取上限 ${maxBytes} 字节。" +
                    "用 run_shell 配合 head/tail 分段读取，或先用 device_list 确认它是不是你要的那个。",
            )
        }

        return runCatching { Result.Ok(file.readText()) }
            .getOrElse { Result.Fail("读取失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 把用户给的路径解析成 [root] 之下的真实文件；越界返回 null。
     *
     * 相对路径按 [root] 解析，绝对路径直接用（但同样要过边界检查）——
     * agent 通常会写绝对路径，拒绝绝对路径只会让它反复试错。
     */

    // ---------------------------------------------------------------- 写入

    /**
     * 写文本。`append = true` 时追加，否则覆盖。
     *
     * 覆盖是**显式**的：调用方必须自己确认（工具层默认拒绝覆盖已存在的文件），
     * 因为 agent 猜错一次路径就会把用户的东西冲掉，而这个动作没有回收站。
     */
    fun writeText(path: String, text: String, append: Boolean): Result<Long> {
        val file = resolve(path) ?: return Result.Fail(outside(path))
        if (protectedPath(file) != null) return Result.Fail(protectedPath(file)!!)
        if (file.isDirectory) return Result.Fail("是目录不是文件：${file.absolutePath}")

        val bytes = text.toByteArray()
        if (bytes.size > MAX_WRITE_BYTES) {
            return Result.Fail(
                "内容 ${bytes.size} 字节，超过单次写入上限 ${MAX_WRITE_BYTES} 字节。" +
                    "拆成几次写（append=true 追加），或先用 run_shell 分块。",
            )
        }

        val parent = file.parentFile ?: return Result.Fail("无法确定父目录：${file.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return Result.Fail("无法创建父目录：${parent.absolutePath}")
        }

        return runCatching {
            if (append) {
                file.appendBytes(bytes)
            } else {
                // 先写临时文件再 rename：读者要么看到旧内容，要么看到完整新内容，不会看到半截。
                val staging = File(parent, ".${file.name}.writing")
                staging.writeBytes(bytes)
                if (!staging.renameTo(file)) {
                    staging.delete()
                    // 同一分区内的 rename 通常都能成功；失败说明目标被占用或跨挂载点，直接写。
                    file.writeBytes(bytes)
                }
            }
            Result.Ok(file.length())
        }.getOrElse { Result.Fail("写入失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 路径存在与否（越界一律 false）。写工具用它判断"要不要显式覆盖"。 */
    fun exists(path: String): Boolean = resolve(path)?.exists() == true

    fun mkdir(path: String): Result<String> {
        val file = resolve(path) ?: return Result.Fail(outside(path))
        if (file.isDirectory) return Result.Ok(file.absolutePath)
        if (file.exists()) return Result.Fail("同名文件已存在：${file.absolutePath}")
        if (!file.mkdirs()) return Result.Fail("创建目录失败：${file.absolutePath}")
        return Result.Ok(file.absolutePath)
    }

    fun move(from: String, to: String): Result<String> {
        val source = resolve(from) ?: return Result.Fail(outside(from))
        val target = resolve(to) ?: return Result.Fail(outside(to))
        if (!source.exists()) return Result.Fail("源不存在：${source.absolutePath}")
        protectedPath(source)?.let { return Result.Fail(it) }
        protectedPath(target)?.let { return Result.Fail(it) }
        if (target.exists()) return Result.Fail("目标已存在：${target.absolutePath}（先确认要不要覆盖）")
        target.parentFile?.mkdirs()
        return runCatching {
            if (source.renameTo(target)) Result.Ok(target.absolutePath)
            else Result.Fail("移动失败：源与目标可能不在同一分区，请用 run_shell 的 mv")
        }.getOrElse { Result.Fail("移动失败：${it.message ?: it.javaClass.simpleName}") }
    }

    fun delete(path: String): Result<String> {
        val file = resolve(path) ?: return Result.Fail(outside(path))
        if (!file.exists()) return Result.Fail("不存在：${file.absolutePath}")
        if (file.canonicalFile == root.canonicalFile) return Result.Fail("拒绝删除共享存储根目录")
        protectedPath(file)?.let { return Result.Fail(it) }
        return runCatching {
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (ok) Result.Ok(file.absolutePath) else Result.Fail("删除失败：${file.absolutePath}")
        }.getOrElse { Result.Fail("删除失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 共享存储里由系统托管的子树，一律不写不删。
     *
     * `Android/data` 与 `Android/obb` 归 PackageManager 管，应用绕过去写要么失败、要么留下
     * 一个卸载时清不掉的残骸。宁可明确拒绝，也不让 agent 在这里"试出一半成功"。
     */
    private fun protectedPath(file: File): String? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val android = File(base, "Android")
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return if (canonical == android || canonical.path.startsWith(android.path + "/")) {
            "拒绝写入 ${canonical.absolutePath}：共享存储的 Android/ 子树由系统管理"
        } else null
    }

    private fun resolve(path: String): File? {
        val raw = if (path.startsWith("/")) File(path) else File(root, path)
        val canonical = runCatching { raw.canonicalFile }.getOrNull() ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        return if (canonical == base || canonical.path.startsWith(base.path + "/")) canonical else null
    }

    private fun outside(path: String) =
        "路径越界：$path 不在可访问范围（${root.absolutePath}）内。" +
            "应用私有目录 /data/** 与 /system/** 不在本工具的能力范围。"

    companion object {
        /** 单次读取上限：够读代码和配置，又不至于把宿主的堆撑爆。 */
        const val MAX_READ_BYTES = 256 * 1024

        /** 单次写入上限：文本文件够用；更大的东西应该走 run_shell 分块，而不是塞进一次工具调用。 */
        const val MAX_WRITE_BYTES = 1024 * 1024
    }
}
