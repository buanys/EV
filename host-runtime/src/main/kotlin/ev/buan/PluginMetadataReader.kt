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
    val dependencies: List<String> = emptyList(),
    /** Capabilities declared in metadata, split into known/unknown names. */
    val permissions: PermissionDeclaration = PermissionDeclaration(),
    /** Desktop entries the container wants the host to be able to pin. */
    val shortcuts: List<ShortcutDeclaration> = emptyList(),
)


internal class PluginMetadataReader(private val context: Context) {

    fun read(file: File): PluginMeta? =
        if (file.extension.lowercase() == "apk") readApkMetadata(file) else readSidecarMetadata(file)

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
        return PluginMeta(
            id = info.packageName,
            version = info.versionName ?: "1.0.0",
            entryClass = entry,
            apiVersion = meta.getInt(API_META, 0),
            dependencies = apkDependencies(meta.getString(DependencyMetadataParser.APK_METADATA_KEY)),
            permissions = PermissionMetadataParser.parseApkMetadata(
                raw = meta.getString(PermissionMetadataParser.APK_METADATA_KEY),
                supported = HostPermission.wireNames,
            ),
        )
    }

    private fun readSidecarMetadata(file: File): PluginMeta? {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (!sidecar.isFile) return null
        val json = runCatching { JSONObject(sidecar.readText()) }.getOrNull() ?: return null
        return PluginMeta(
            id = json.getString("id"),
            version = json.optString("version", "1.0.0"),
            entryClass = json.getString("entryClass"),
            apiVersion = json.optInt("apiVersion", 0),
            dependencies = DependencyMetadataParser.normalize(
                json.stringList(DependencyMetadataParser.JAR_SIDECAR_KEY),
            ),
            permissions = PermissionMetadataParser.parseJarMetadata(
                declared = json.stringList(PermissionMetadataParser.JAR_SIDECAR_KEY),
                supported = HostPermission.wireNames,
            ),
            shortcuts = parseShortcuts(json.optJSONArray("shortcuts")),
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
        const val ENTRY_META = "cordis.entry"
        const val API_META = "cordis.api"
    }
}
