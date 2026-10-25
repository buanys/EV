package ev.buan.plugins.agent.tools

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.PluginStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The file tools offered to the agent, all routed through the host-provided [PluginStorage].
 *
 * Confinement is deliberately not re-implemented here: [PluginStorage] already rejects absolute
 * paths and any `.` / `..` segment, so the path policy lives in exactly one place. A rejected path,
 * a quota overflow or malformed model output comes back as an error result instead of an exception:
 * one bad tool call must not abort the caller's whole agent turn.
 */
internal object StorageTools {
    /** A result text is fed back into the next model request, so keep it below any context window. */
    private const val MAX_READ_BYTES = 64 * 1024

    /** One call may not eat the 20 MB quota; a model writing more than this is a bug, not intent. */
    private const val MAX_WRITE_BYTES = 512 * 1024

    /** Enough of a prefix to recognise a binary file without scanning a large one. */
    private const val BINARY_PROBE_BYTES = 4096

    fun all(storage: PluginStorage): List<Tool> = listOf(
        FileTool(StorageToolSpecs.read) { arguments -> read(storage, arguments) },
        FileTool(StorageToolSpecs.write) { arguments -> write(storage, arguments) },
        FileTool(StorageToolSpecs.list) { arguments -> list(storage, arguments) },
        FileTool(StorageToolSpecs.delete) { arguments -> delete(storage, arguments) },
    )

    private fun read(storage: PluginStorage, arguments: JSONObject): String {
        val path = pathOf(arguments)
        val bytes = storage.read(path) ?: return failureJson("文件不存在：$path")
        val head = bytes.copyOf(minOf(bytes.size, BINARY_PROBE_BYTES))
        if (head.any { it == 0.toByte() }) {
            return failureJson("$path 看起来是二进制文件，这些工具只处理文本")
        }
        val truncated = bytes.size > MAX_READ_BYTES
        val text = bytes.copyOf(minOf(bytes.size, MAX_READ_BYTES)).decodeToString()
        return if (truncated) "$text\n…（内容已截断，原文件 ${bytes.size} 字节）" else text
    }

    private fun write(storage: PluginStorage, arguments: JSONObject): String {
        val path = pathOf(arguments)
        require(arguments.has("content") && !arguments.isNull("content")) { "缺少 content 参数" }
        val bytes = arguments.getString("content").encodeToByteArray()
        if (bytes.size > MAX_WRITE_BYTES) {
            return failureJson("单次写入上限 $MAX_WRITE_BYTES 字节，本次 ${bytes.size} 字节")
        }
        // Atomic so a concurrent reader sees either the old or the new content, never a half file.
        storage.atomicWrite(path, bytes)
        return JSONObject().put("path", path).put("bytes", bytes.size).toString()
    }

    private fun list(storage: PluginStorage, arguments: JSONObject): String {
        val path = arguments.optString("path", "")
        val entries = JSONArray()
        storage.list(path).forEach { entries.put(it) }
        return JSONObject().put("path", path).put("entries", entries).toString()
    }

    private fun delete(storage: PluginStorage, arguments: JSONObject): String {
        val path = pathOf(arguments)
        return JSONObject().put("path", path).put("deleted", storage.delete(path)).toString()
    }

    private fun pathOf(arguments: JSONObject): String {
        val path = arguments.optString("path", "")
        require(path.isNotBlank()) { "缺少 path 参数（存储区内的相对路径）" }
        return path
    }
}

/**
 * One named tool whose arguments arrive as the model's JSON object text.
 *
 * [operation] runs synchronously: [PluginStorage] is blocking by contract and these files are
 * small, so the tool needs no dispatcher of its own.
 */
internal class FileTool(
    override val spec: ToolSpec,
    private val operation: (JSONObject) -> String,
) : Tool {
    override suspend fun invoke(argumentsJson: String): String = runCatching {
        operation(argumentsOf(argumentsJson))
    }.getOrElse { error ->
        failureJson("${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}")
    }
}

private fun argumentsOf(argumentsJson: String): JSONObject =
    if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)

private fun failureJson(message: String): String = JSONObject().put("error", message).toString()
