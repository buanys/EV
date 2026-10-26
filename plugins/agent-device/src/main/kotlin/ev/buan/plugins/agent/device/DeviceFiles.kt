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
    }
}
