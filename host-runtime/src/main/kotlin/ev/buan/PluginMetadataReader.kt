package ev.buan

import android.content.Context
import android.content.pm.PackageManager
import ev.buan.hostcore.dependency.DependencyMetadataParser
import ev.buan.hostcore.permission.PermissionDeclaration
import ev.buan.hostcore.permission.PermissionMetadataParser
import org.cordis.api.HostPermission
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个容器声明的全部元数据：File 进去，数据出来。
 *
 * 从 `PluginInstaller` 拆出来是因为它**只读**。以前它和扫描、校验、发布挤在同一个
 * 680 行的类里，"这个字段到底从哪来"要顺着三条路径翻；现在只有一处，
 * 而且不需要造一个安装器就能验证。
 */
internal data class PluginMeta(
    val id: String,
    val version: String,
    val entryClass: String,
    val apiVersion: Int,
    /**
     * 容器声明的宿主 API 兼容范围原文；null 表示没声明，回落到 [apiVersion] 的整数门。
     *
     * 只读原文、不在这里解析：解析与判定归 `host-core` 的 `HostApiRange`，
     * 校验器是唯一该说"这个范围合不合法"的地方。
     */
    val hostApi: String? = null,
    val dependencies: List<String> = emptyList(),
    /** Capabilities declared in metadata, split into known/unknown names. */
    val permissions: PermissionDeclaration = PermissionDeclaration(),
    /** Desktop entries the container wants the host to be able to pin. */
    val shortcuts: List<ShortcutDeclaration> = emptyList(),
    /**
     * 容器自己声明"我是工具类插件，请在独立进程里跑"。
     *
     * 必须由插件声明，不能由宿主从权限猜：插件拿宿主服务走的是 `ServiceDirectory` 查表，
     * 声明里看不出它查了谁。第一版按权限推断，结果 17 个插件里 12 个被塞进子进程、
     * 全部因为拿不到宿主服务而失败——这就是"猜"的代价。
     */
    val separateProcess: Boolean = false,
    /**
     * 容器声明的能力项；空表示"必须常驻"，启动即激活。
     *
     * 有了它，宿主不装载容器也能回答"这个查找有没有人能接"——懒加载的前提。
     */
    val provides: Set<String> = emptySet(),
    /** 声明了但宿主不认识的能力项，扫描时作为提示回报，不阻塞。 */
    val rejectedProvides: List<String> = emptyList(),
)


internal class PluginMetadataReader(private val context: Context) {

    fun read(file: File): PluginMeta? = when (file.extension.lowercase()) {
        "apk" -> readApkMetadata(file)
        "jar", "dex" -> readJarMetadata(file)
        else -> null
    }

    /**
     * 容器自带的元数据：**先看 jar 内部**，再回落到同目录的 `.json`。
     *
     * 插件是分发单元，所以它应该是**一个文件**——用户选一个 jar 就够，不需要再配一个
     * 同名 json，"选错配对"这类错误从根上消失。回落那条只为老容器留着：它们的内嵌
     * 元数据还没补上，重打一次就能删掉这个分支。
     */
    private fun readJarMetadata(file: File): PluginMeta? {
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry(EMBEDDED_META) ?: zip.getEntry(LEGACY_EMBEDDED_META)
                if (entry != null) {
                    val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                    return parseJsonMeta(JSONObject(text))
                }
            }
        }
        return readSidecarMetadata(file)
    }

    private fun readSidecarMetadata(file: File): PluginMeta? {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (!sidecar.isFile) return null
        val json = runCatching { JSONObject(sidecar.readText()) }.getOrNull() ?: return null
        return parseJsonMeta(json)
    }

    /** sidecar 与内嵌元数据共用同一套字段解析：两种来源，一份真相。 */
    private fun parseJsonMeta(json: JSONObject): PluginMeta? {
        val provides = ProvidesSpec.parse(json.stringList(ProvidesSpec.SIDECAR_KEY).orEmpty())
        return runCatching {
            PluginMeta(
                id = json.getString("id"),
                version = json.optString("version", "1.0.0"),
                entryClass = json.getString("entryClass"),
                apiVersion = json.optInt("apiVersion", 0),
                hostApi = if (json.has("hostApi")) json.optString("hostApi").takeIf { it.isNotBlank() } else null,
                dependencies = DependencyMetadataParser.normalize(
                    json.stringList(DependencyMetadataParser.JAR_SIDECAR_KEY),
                ),
                permissions = PermissionMetadataParser.parseJarMetadata(
                    declared = json.stringList(DependencyMetadataParser.JAR_SIDECAR_KEY),
                    supported = HostPermission.wireNames,
                ),
                shortcuts = parseShortcuts(json.optJSONArray("shortcuts")),
                separateProcess = json.optString(ISOLATION_KEY) == ISOLATION_SEPARATE,
                provides = provides.items,
                rejectedProvides = provides.rejected,
            )
        }.getOrNull()
    }

    /**
     * Reads package name, version, entry class, API version, declared dependencies and declared
     * permissions from the APK manifest. The host never installs the archive, so everything has to
     * come from metadata.
     */
    private fun readApkMetadata(file: File): PluginMeta? {
        if (!file.isFile) return null
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            file.path,
            PackageManager.GET_META_DATA,
        ) ?: return null
        val meta = info.applicationInfo?.metaData ?: return null
        val entry = meta.getString(ENTRY_META) ?: return null
        val provides = ProvidesSpec.parse(
            ProvidesSpec.splitMetaValue(meta.getString(ProvidesSpec.APK_META_KEY)),
        )
        return PluginMeta(
            id = info.packageName,
            version = info.versionName ?: "1.0.0",
            entryClass = entry,
            apiVersion = meta.getInt(API_META, 0),
            hostApi = meta.getString(HOST_API_META),
            dependencies = apkDependencies(meta.getString(DependencyMetadataParser.APK_METADATA_KEY)),
            separateProcess = meta.getString(ISOLATION_KEY) == ISOLATION_SEPARATE,
            provides = provides.items,
            rejectedProvides = provides.rejected,
            permissions = PermissionMetadataParser.parseApkMetadata(
                raw = meta.getString(PermissionMetadataParser.APK_METADATA_KEY),
                supported = HostPermission.wireNames,
            ),
        )
    }

    
    /**
     * 只认完整声明：缺 id 或 label 的条目跳过，不猜。
     *
     * 一个坏条目不该让整个插件装不上——快捷方式是增值项，不是运行前提。
     */
    private fun parseShortcuts(array: org.json.JSONArray?): List<ShortcutDeclaration> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id", "").trim()
            val label = item.optString("label", "").trim()
            if (id.isEmpty() || label.isEmpty()) return@mapNotNull null
            ShortcutDeclaration(
                id = id,
                label = label,
                icon = item.optString("icon", "").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun JSONObject.stringList(key: String): List<String>? =
        optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf(String::isNotBlank)
            }
        }

    /**
     * Reads `cordis.dependencies` from an APK: a JSON array *string* because Android meta-data holds
     * strings, e.g. `["ev.buan.plugins.agent.api"]`.
     *
     * A malformed value is a metadata error rather than "no dependencies": a container whose links
     * cannot be parsed would otherwise be accepted without them ever being ordered or linked.
     */
    private fun apkDependencies(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse {
            throw IllegalArgumentException(
                "${DependencyMetadataParser.APK_METADATA_KEY} 必须是 JSON 数组字符串（实际：$raw）",
            )
        }
        val declared = (0 until array.length()).map { index ->
            array.opt(index) as? String ?: throw IllegalArgumentException(
                "${DependencyMetadataParser.APK_METADATA_KEY} 第 ${index + 1} 项不是插件 id 字符串",
            )
        }
        return DependencyMetadataParser.normalize(declared)
    }

    private companion object {
        /** 容器内嵌元数据的位置：与 MANIFEST 同级，一看就知道是元数据。 */
        const val EMBEDDED_META = "META-INF/plugin.json"

        /** 兼容位置：有些打包脚本习惯直接放根目录。 */
        const val LEGACY_EMBEDDED_META = "plugin.json"

        /** sidecar / APK 元数据里"要在独立进程里跑"的声明键与取值。 */
        const val ISOLATION_KEY = "isolation"
        const val ISOLATION_SEPARATE = "separate-process"

        const val ENTRY_META = "cordis.entry"
        const val API_META = "cordis.api"

        /**
         * 插件声明宿主 API 兼容范围的键（语义化比较符字符串，如 `>=1.0.0 <2.0.0`）。
         *
         * APK 走 manifest meta-data，jar 走 sidecar 的同名字段；两处拼写必须一致，
         * 否则"同一个插件打成两种容器，一种能装一种装不上"。
         */
        const val HOST_API_META = "cordis.hostApi"
    }
}
