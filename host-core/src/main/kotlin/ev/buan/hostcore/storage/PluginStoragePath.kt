package ev.buan.hostcore.storage

import java.io.File

/**
 * Relative-path rules for the per-plugin storage API.
 *
 * Kept as pure string logic so the host can unit-test every rejected shape without touching the
 * file system. The rules are deliberately strict: canonical form only, POSIX separators only, no
 * `.` / `..` segments, no drive letters and no control characters. A rejected path is never
 * "cleaned up" silently, because a plugin that asks for `../x` has a bug worth surfacing.
 */
object PluginStoragePath {

    /** Normalizes [relative] or throws [IllegalArgumentException] describing the first violation. */
    fun normalize(relative: String): String {
        require(relative.isNotEmpty()) { "存储路径不能为空" }
        require(!relative.startsWith('/')) { "存储路径必须是相对路径：$relative" }
        require(!relative.contains('\\')) { "存储路径必须使用 '/' 分隔：$relative" }
        require(!relative.contains(':')) { "存储路径不能包含驱动器/设备前缀：$relative" }
        require(relative.none { it.code < 0x20 || it.code == 0x7F }) { "存储路径包含控制字符：$relative" }
        val segments = relative.split('/')
        require(segments.none { it.isEmpty() }) { "存储路径不能包含空段：$relative" }
        require(segments.none { it == "." || it == ".." }) { "存储路径不能包含 '.' 或 '..'：$relative" }
        return relative
    }

    /** Resolves [relative] under [root] and proves the canonical file stays inside [root]. */
    fun resolve(root: File, relative: String): File {
        val file = File(root, normalize(relative))
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        val inside = canonical == canonicalRoot ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
        require(inside) { "存储路径越界：$relative" }
        return canonical
    }
}
