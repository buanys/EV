package ev.buan

import android.content.Context
import android.os.Environment
import ev.buan.hostcore.plugin.PluginId
import java.io.File

/**
 * 宿主的插件文件系统：外部 `EV/mods`、私有 `files/mods`、代码缓存，以及它们之间的搬运。
 *
 * 从 `PluginInstaller` 拆出来，因为这一块和"装不装"无关——它只知道**字节放哪儿**：
 *
 *  - 外部目录是投放口（人拷进来、forge 写进来），装卸都在这里；
 *  - 私有目录是加载口，只读挂锁，防止容器被就地改写；
 *  - 代码缓存按 (id, version) 分目录，dexopt 产物过期就是白占空间。
 *
 * 三条路径以前散在安装器 700 行里，卸载要同时动两个目录时最容易漏掉一个——
 * 现在同一条规则只有一处。
 *
 * 两个"锁"的时机是这里唯一的时序要求：`sync` 开始时打开私有目录的可写位（
 * 否则 rename 进不去），结束时立刻锁回只读。写在这一层，是因为只有这一层知道
 * 什么时候有写入在发生。
 */
internal class PluginFiles(
    private val context: Context,
    private val privateRoot: File = File(context.filesDir, "mods"),
    private val codeCacheRoot: File = File(context.codeCacheDir, "cordis-plugins"),
) {

    /** The drop-box: anything with storage access can put a container here. */
    val externalRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "EV/mods")

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** `sync` 第一件事：私有目录必须可写，否则 rename 与删除都会静默失败。 */
    fun beginSync() {
        privateRoot.mkdirs()
        privateRoot.setWritable(true)
    }

    /** `sync` 最后一件事：锁回只读。容器一旦发布就不该被就地改写。 */
    fun endSync() {
        privateRoot.setReadOnly()
    }

    /**
     * Deletes one container from the external mods directory: the jar and its sidecar json.
     *
     * Plugin *storage* is deliberately untouched - "卸载" removes the code, not the user's data,
     * and the management page offers "清理" separately for that. The private mirror is refreshed by
     * the next sync, which no longer finds these files and drops them.
     *
     * Returns the names that were actually removed, so the caller can report a partial failure
     * instead of claiming success.
     */
    fun removeExternal(id: String): List<String> {
        val targets = listOf("$id.jar", "$id.json")
        val removed = mutableListOf<String>()
        for (name in targets) {
            val file = File(externalRoot, name)
            if (file.isFile && file.delete()) removed += name
        }
        return removed
    }

    /**
     * 删掉这个 id 在私有目录里的镜像副本。
     *
     * 卸载时用：外部那份删掉之后，私有这份要等到下一次 sync 才被 prune，
     * 中间它继续占着几十 MB（载荷类容器一份 27 MB），插件页里也还是一张卡片。
     * 路径必须走 [targetFor]——与写入时同一个函数，拼字符串错一次就是"删了个寂寞"。
     */
    fun removePrivate(id: String): Boolean {
        val file = targetFor(id)
        if (!file.exists()) return false
        // 目录平时是 r-x：宿主在 sync 结束时锁住，防止容器被就地改写。
        // unlink 需要的是**目录**可写，所以这里短暂解锁、删、立刻锁回去——与 sync 同一套写法。
        privateRoot.setWritable(true)
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        privateRoot.setReadOnly()
        if (!deleted) android.util.Log.w("EV", "removePrivate 失败：${file.absolutePath}（由下次 sync 的 prune 收尾）")
        return deleted
    }

    /**
     * Copies a user-picked container into the external mods directory.
     *
     * The name is kept verbatim: sidecar lookup is by the jar's own file name, so renaming here
     * would silently detach a jar from its metadata. An existing file is replaced only when
     * [overwrite] is set, so importing twice is an explicit decision.
     */
    fun importExternal(source: File, fileName: String, overwrite: Boolean): File? {
        if (!externalRoot.isDirectory && !externalRoot.mkdirs()) return null
        val target = File(externalRoot, fileName)
        if (target.exists() && !overwrite) return null
        return runCatching {
            source.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    /** 这个 id 在私有目录里应该待的位置。 */
    fun targetFor(id: String): File {
        // Defense in depth: collect validates the id, but the file-name helper is the last line
        // between an id and the private root, so it re-checks instead of trusting the caller.
        val name = PluginId.fileName(id)
        privateRoot.mkdirs()
        return File(privateRoot, name)
    }

    /**
     * 把一份字节发布到 [target]：先写临时文件、校验哈希、再 rename。
     *
     * 不直接覆盖目标：加载器可能正拿着旧文件，且 rename 在同一文件系统上是原子的——
     * 要么是完整的旧版本，要么是完整的新版本，不会有半个 jar 被读到。
     */
    fun publish(target: File, source: File, id: String, hash: String) {
        val temporary = File(privateRoot, PluginId.stagingFileName(id))
        delete(temporary)
        source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
        check(androidModuleHash(temporary) == hash) { "复制后哈希不一致: $id" }
        temporary.setReadOnly()
        delete(target)
        check(temporary.renameTo(target)) { "无法发布 $target" }
    }

    /** Read-only files cannot be deleted directly, so reopen them first. */
    fun delete(file: File) {
        if (!file.exists()) return
        file.setWritable(true)
        check(file.delete()) { "无法删除 $file" }
    }

    /** 从 assets 里解出一个内置容器到缓存目录，返回那份文件的路径。 */
    fun stageAsset(name: String): File {
        val staged = File(context.cacheDir, "seed/$name")
        staged.parentFile?.mkdirs()
        if (!staged.isFile) {
            context.assets.open("plugins/android/$name").use { input ->
                staged.outputStream().use(input::copyTo)
            }
        }
        return staged
    }

    /**
     * 清掉这一轮没收下的东西：私有目录里多余的容器，以及过期版本的代码缓存。
     *
     * 两件事放一起，是因为它们的判据相同——**这一轮没被接受的就不该留**。
     * 失败写进 [errors] 而不是抛：删不掉一个旧文件，不该让整个扫描失败。
     */
    fun prune(expectedIds: Set<String>, currentVersions: Map<String, String>, errors: MutableList<String>) {
        privateRoot.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val stale = file.name.startsWith(".") || file.nameWithoutExtension !in expectedIds
            if (stale) {
                runCatching { delete(file) }.onFailure { errors += "清理 ${file.name} 失败: $it" }
            }
        }
        codeCacheRoot.listFiles()?.forEach { idDir ->
            val currentVersion = currentVersions[idDir.name]
            if (currentVersion == null) {
                runCatching { idDir.deleteRecursively() }
                    .onFailure { errors += "清理缓存 ${idDir.name} 失败: $it" }
                return@forEach
            }
            idDir.listFiles()?.forEach { versionDir ->
                if (versionDir.name != currentVersion) {
                    runCatching { versionDir.deleteRecursively() }
                        .onFailure { errors += "清理缓存 ${idDir.name}/${versionDir.name} 失败: $it" }
                }
            }
        }
    }
}
