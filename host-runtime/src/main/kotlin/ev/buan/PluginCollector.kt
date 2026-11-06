package ev.buan

import android.content.Context
import ev.buan.hostcore.plugin.PluginId
import java.io.File

/**
 * 扫描：把"哪里可能有容器"变成一份待排队的清单。
 *
 * 两个来源，两条规矩：
 *
 *  - 内置（assets）：先解到缓存再看元数据，一个坏了只记它自己；
 *  - 外部（`EV/mods`）：**没有「所有文件访问权限」就整条跳过并说明**——静默返回空列表
 *    会让人以为"没有插件"，而真正的原因是没有权限。
 *
 * 这一层只收集，不判断装不装：id 合法就先放行，接受与否是后面 `InstallDecision` 的事。
 * 拆出来是因为它只依赖 context / 文件 / 元数据读取三样，与安装器那 700 行的状态无关。
 */

/** One container that parsed cleanly and is waiting for its place in the accept order. */
internal data class PluginSource(
    val file: File,
    val meta: PluginMeta,
    /** 谁把它放进来的：assets 还是外部目录。信任判定要用，且只有这里知道。 */
    val origin: ev.buan.hostcore.trust.PluginOrigin,
)

internal class PluginCollector(
    private val context: Context,
    private val files: PluginFiles,
    private val metadata: PluginMetadataReader,
) {

fun bundled(sources: MutableList<PluginSource>, errors: MutableList<String>) {
    val names = runCatching { context.assets.list("plugins/android")?.toList().orEmpty() }
        .getOrElse {
            errors += "读取内置插件失败: $it"
            return
        }
    // Assets have no meaningful order of their own; the listing is kept stable for reproducible
    // messages, but it no longer decides when a container is accepted.
    names.filter { it.endsWith(".apk") }.sorted().forEach { name ->
        runCatching {
            val staged = files.stageAsset(name)
            val meta = metadata.read(staged) ?: error("缺少 cordis.entry 元数据")
            // The id becomes a private file name and a loader module id; reject it before
            // anything is copied or written. The error stays per-asset, not a crash.
            PluginId.requireValid(meta.id)
            sources += PluginSource(staged, meta, ev.buan.hostcore.trust.PluginOrigin.BUILT_IN)
            tracePlugin("scanned (bundled) ${meta.id}")
        }.onFailure { errors += "内置插件 $name: $it" }
    }
}

/**
 * Collects `<external storage>/EV/mods`, accepting nothing.
 *
 * The listing order is whatever the filesystem returns; two containers are only ordered against
 * each other when one of them declares the other as a dependency.
 */
fun external(sources: MutableList<PluginSource>, errors: MutableList<String>) {
    val external = files.externalRoot
    if (!files.hasAllFilesAccess()) {
        errors += "未授予「所有文件访问权限」，跳过扫描 ${external.path}"
        return
    }
    if (!external.isDirectory && !external.mkdirs()) {
        errors += "无法创建 ${external.path}"
        return
    }
    external.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
        ?.forEach { source ->
            runCatching {
                val meta = metadata.read(source)
                    ?: error("缺少元数据（APK 需 cordis.entry/cordis.api，jar/dex 需同名 .json）")
                // Fail closed before accept/publish: an id containing a path separator, `..`,
                // a drive prefix or control characters must never become a file name.
                PluginId.requireValid(meta.id)
                sources += PluginSource(source, meta, ev.buan.hostcore.trust.PluginOrigin.EXTERNAL)
                tracePlugin("scanned ${meta.id} from ${source.name}")
            }.onFailure { errors += "${source.name}: $it" }
        }
}

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("apk", "jar", "dex")
    }
}
