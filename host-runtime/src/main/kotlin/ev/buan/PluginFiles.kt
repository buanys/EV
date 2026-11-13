package ev.buan

import android.content.Context
import ev.buan.hostcore.plugin.PluginId
import java.io.File

/**
 * 宿主的插件文件系统：私有 `files/mods`、代码缓存。
 *
 * **没有外部投放目录了。** 从 2026-11-13 起插件只从应用内部安装：用户选文件 →
 * 宿主自己把字节写进私有目录 → 扫描读同一份。这样做的理由很实际：
 *
 *  - 外部目录里的文件属主是"谁拷进来的"，不是 EV；`/sdcard` 由 MediaProvider 管，
 *    `chmod`/`chown` 都不生效，于是"读不到"和"没装"在界面上长得一模一样；
 *  - 私有目录里的文件属主就是 EV 自己，权限问题从根上不存在。
 *
 * 私有目录现在同时存 jar 和它的 sidecar（`<id>.jar` + `<id>.json`）：元数据不再
 * 依赖一个可被外部改写的位置。
 *
 * 两个"锁"的时机是这里唯一的时序要求：写入前打开私有目录的可写位，
 * 写完立刻锁回只读——容器一旦发布就不该被就地改写。
 */
internal class PluginFiles(
    private val context: Context,
    private val privateRoot: File = File(context.filesDir, "mods"),
    private val codeCacheRoot: File = File(context.codeCacheDir, "cordis-plugins"),
) {

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
        val deleted = runCatching {
            val json = sidecarFor(id)
            if (json.isFile) json.delete()
            file.delete()
        }.getOrDefault(false)
        privateRoot.setReadOnly()
        if (!deleted) android.util.Log.w("EV", "removePrivate 失败：${file.absolutePath}（由下次 sync 的 prune 收尾）")
        return deleted
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
    fun publish(
        target: File,
        source: File,
        id: String,
        hash: String,
        sidecar: String,
        previousSidecar: String? = null,
    ) {
        // 覆盖之前先留一份上一版：回滚是恢复章里唯一的"退回去"手段，
        // 而旧字节一旦被 rename 覆盖就再也拿不回来。
        if (target.isFile) archivePrevious(id, target, previousSidecar)
        val temporary = File(privateRoot, PluginId.stagingFileName(id))
        delete(temporary)
        source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
        check(androidModuleHash(temporary) == hash) { "复制后哈希不一致: $id" }
        temporary.setReadOnly()
        delete(target)
        check(temporary.renameTo(target)) { "无法发布 $target" }
        // sidecar 与容器放一起：它是元数据的唯一来源，不能再依赖外部那份 json。
        val sidecarFile = sidecarFor(id)
        val sidecarTemporary = File(privateRoot, "${PluginId.stagingFileName(id)}.json")
        runCatching {
            sidecarTemporary.writeText(sidecar)
            sidecarFile.delete()
            check(sidecarTemporary.renameTo(sidecarFile)) { "无法发布 $sidecarFile" }
            sidecarFile.setReadOnly()
        }
    }

    /**
     * 私有目录里所有容器。**扫描的唯一来源**：现在没有外部投放口了。
     *
     * 只认 `.jar`：sidecar（`.json`）与容器同名，[PluginMetadataReader] 会自己去找它，
     * 这里不能把它当成一个待解析的容器。
     */
    fun privateContainers(): List<File> =
        privateRoot.listFiles()
            // 只要不是 sidecar、不是隐藏文件，就是容器。不按扩展名白名单过滤：
            // 之前只认 .jar，而写进去的叫 .apk，于是"装好了"和"目录是空的"看起来一样。
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 一个文件属于哪个容器 id：`a.jar`、`a.jar.json`、`a.apk` 都归到 `a`。 */
    private fun containerIdOf(file: File): String {
        var name = file.name
        if (name.endsWith(".json", ignoreCase = true)) name = name.dropLast(5)
        for (suffix in listOf(".jar", ".apk", ".dex")) {
            if (name.endsWith(suffix, ignoreCase = true)) return name.dropLast(suffix.length)
        }
        return name
    }

    /** 这个 id 的 sidecar 在私有目录里的位置。 */
    fun sidecarFor(id: String): File = File(privateRoot, "${PluginId.fileName(id)}.json")

    /** 上一版存档的位置；与私有容器同根，但 `prune` 跳过目录，不会被当成陈旧文件删掉。 */
    private fun previousRoot(): File = File(privateRoot, "previous").apply { mkdirs() }

    /**
     * 把即将被覆盖的字节连同它的 sidecar 存一份。
     *
     * sidecar 由调用方按当前运行中的元数据重建——私有目录里只有 jar，原本的 json 早就在外部
     * 被新版本盖掉了。重建出来的一对文件足以让回滚后的容器重新装载。
     */
    private fun archivePrevious(id: String, jar: File, sidecar: String?) {
        val dir = previousRoot()
        val name = PluginId.fileName(id)
        runCatching {
            jar.copyTo(File(dir, "$name.jar"), overwrite = true)
            // sidecar 只是附带的线索（人看得出版本号），回滚不依赖它：
            // 外部那份 json 在扫描到新版本时已经被覆盖，宿主手里没有"上一版的声明"。
            if (sidecar != null) File(dir, "$name.json").writeText(sidecar)
        }
    }

    /** 这个插件有没有可回滚的上一版。 */
    fun hasPrevious(id: String): Boolean =
        File(previousRoot(), "${PluginId.fileName(id)}.jar").isFile

    /** 上一版的容器字节；没有存档时返回 null。 */
    fun previousOf(id: String): File? =
        File(previousRoot(), "${PluginId.fileName(id)}.jar").takeIf { it.isFile }

    /**
     * 把上一版字节写回私有目录，返回真正写进去的文件名；没有存档时返回空列表。
     *
     * **只回退字节，不改 sidecar**：sidecar 现在和容器一起待在私有目录，回滚时用的是
     * 当前那份声明（版本号、依赖都是新的），只有 jar 是旧的。新版本起不来、类没变
     * 这一类场景够用；如果新版本改过依赖，回滚后要人再看一眼。
     */
    fun restorePrevious(id: String, jarName: String): List<String> {
        val jar = previousOf(id) ?: return emptyList()
        val written = mutableListOf<String>()
        runCatching {
            privateRoot.setWritable(true)
            jar.copyTo(targetFor(id), overwrite = true)
            written += jarName
        }.also {
            privateRoot.setReadOnly()
        }
        return written
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
            // sidecar 跟着容器走：`a.jar.json` 的 id 是 `a`，不是 `a.jar`。
            val stale = file.name.startsWith(".") || containerIdOf(file) !in expectedIds
            if (stale) {
                // 本轮没接受任何容器时，这里会把整个目录清空——所以必须留下是谁删的、为什么。
                tracePlugin("清理 ${file.name}（不在本轮 ${expectedIds.size} 个已接受之内）")
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
