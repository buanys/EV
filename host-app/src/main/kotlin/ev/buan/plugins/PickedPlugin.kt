package ev.buan.plugins

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 从用户选中的文件里取出一个插件。
 *
 * **只要一个 jar。** 元数据（`META-INF/plugin.json`）在容器内部，所以不存在
 * "选了 jar 忘了选 json" 或者 "选错了配对" 这两种失败——插件是分发单元，
 * 一个文件就该是一次安装。
 *
 * 只做取字节和读元数据，不碰安装：校验、签名、落盘归
 * [org.cordis.api.PluginInstallService]，这里不该重复一遍。
 */
internal object PickedPlugin {

    /** 容器内嵌元数据的位置，与 [ev.buan.PluginMetadataReader] 保持一致。 */
    private const val EMBEDDED_META = "META-INF/plugin.json"
    private const val LEGACY_EMBEDDED_META = "plugin.json"

    data class Ready(val jar: ByteArray, val sidecarJson: String, val name: String)

    sealed interface Result {
        data class Ok(val plugin: Ready) : Result
        data class Failed(val reason: String) : Result
    }

    fun read(context: Context, uris: List<Uri>): Result {
        val uri = uris.firstOrNull() ?: return Result.Failed("没有选择文件")
        val name = displayName(context, uri) ?: "plugin.jar"
        if (!name.endsWith(".jar", ignoreCase = true)) {
            return Result.Failed("请选择一个 .jar（插件现在自带元数据）")
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return Result.Failed("读不到这个文件")

        val meta = runCatching { embeddedMeta(bytes) }.getOrNull()
            ?: return Result.Failed("这个 jar 里没有 $EMBEDDED_META，不是 EV 插件")
        return Result.Ok(Ready(bytes, meta, name))
    }

    /** 从 zip 里把元数据原文拿出来；不解析、不校验，那是宿主的活。 */
    private fun embeddedMeta(bytes: ByteArray): String? =
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name == EMBEDDED_META || entry.name == LEGACY_EMBEDDED_META)) {
                    return zip.readBytes().decodeToString()
                }
                entry = zip.nextEntry
            }
            null
        }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
        }.getOrNull()
}
