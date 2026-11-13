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
/**
 * 私有目录里的容器。**外部投放已经不存在了**，所以这是唯一的"已安装"来源。
 *
 * 元数据来自同目录的 sidecar：宿主在安装时把两个文件一起写进私有目录，
 * 因此这里读到的声明和字节永远是同一批、同一个属主。
 */
fun installed(sources: MutableList<PluginSource>, errors: MutableList<String>) {
    val jars = files.privateContainers()
    tracePlugin("私有目录扫描：${jars.size} 个容器")
    if (jars.isEmpty()) return
    jars.forEach { jar ->
        runCatching {
            val meta = metadata.read(jar)
            if (meta == null) {
                // 这里以前静默变成"没有插件"，是排障时最贵的一种沉默。
                tracePlugin("元数据读不出来：${jar.name}（${jar.length()} 字节）")
                error("缺少元数据（jar 内没有 META-INF/plugin.json，也没有同名 .json）")
            }
            PluginId.requireValid(meta.id)
            sources += PluginSource(jar, meta, ev.buan.hostcore.trust.PluginOrigin.EXTERNAL)
            tracePlugin("scanned ${meta.id} from ${jar.name}")
        }.onFailure {
            tracePlugin("解析失败 ${jar.name}：${it.message ?: it::class.simpleName}")
            errors += "${jar.name}: $it"
        }
    }
}

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("apk", "jar", "dex")
    }
}
