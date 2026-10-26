package ev.buan.plugins.agent.device

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备文件工具：让 agent 看见手机上的东西。
 *
 * 和 `agent-tools` 那四个工具的区别是**作用域**：那几个只能碰插件自己的存储区
 * （`files/mods/agent-tools/...`），这四个走的是宿主的共享存储权限
 * （`MANAGE_EXTERNAL_STORAGE`），所以 `Download`、`Documents`、`Pictures` 都读得到。
 *
 * 只读。写和删不提供——v0 没有理由让 agent 在用户手机上改文件。
 */
internal object DeviceTools {

    fun all(files: DeviceFiles): List<Tool> = listOf(
        DeviceTool(LIST_SPEC) { args -> list(files, args) },
        DeviceTool(READ_SPEC) { args -> read(files, args) },
    )

    private val LIST_SPEC = ToolSpec(
        name = "device_list",
        description = "列出手机上某个目录的内容（共享存储，如 /sdcard/Download）。只读。",
        parametersJson = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"目录路径，绝对路径或相对共享存储根。空表示共享存储根目录。"},
              "limit":{"type":"integer","description":"最多返回多少条，默认 100，上限 500"}
            },"required":["path"],"additionalProperties":false}
        """.trimIndent(),
    )

    private val READ_SPEC = ToolSpec(
        name = "device_read",
        description = "读取手机共享存储里的一个文本文件。只读，超过上限请改用 run_shell 分段读。",
        parametersJson = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"文件路径，绝对路径或相对共享存储根"}
            },"required":["path"],"additionalProperties":false}
        """.trimIndent(),
    )

    private fun list(files: DeviceFiles, args: JSONObject): String {
        val path = args.optString("path", "")
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        return when (val result = files.list(path)) {
            is DeviceFiles.Result.Fail -> error(result.error)
            is DeviceFiles.Result.Ok -> {
                val shown = result.value.take(limit)
                JSONObject()
                    .put("root", files.rootPath())
                    .put("path", path.ifEmpty { files.rootPath() })
                    .put("total", result.value.size)
                    .put("truncated", result.value.size > shown.size)
                    .put(
                        "entries",
                        JSONArray().apply {
                            shown.forEach { e ->
                                put(
                                    JSONObject()
                                        .put("name", e.name)
                                        .put("path", e.path)
                                        .put("type", if (e.isDirectory) "dir" else "file")
                                        .put("size", e.size)
                                        .put("mtime", e.modifiedAt),
                                )
                            }
                        },
                    )
                    .toString()
            }
        }
    }

    private fun read(files: DeviceFiles, args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) return error("path 不能为空")
        return when (val result = files.readText(path, DeviceFiles.MAX_READ_BYTES)) {
            is DeviceFiles.Result.Fail -> error(result.error)
            is DeviceFiles.Result.Ok -> JSONObject()
                .put("path", path)
                .put("bytes", result.value.length)
                .put("text", result.value)
                .toString()
        }
    }

    private fun error(message: String): String = JSONObject().put("error", message).toString()

    private class DeviceTool(
        override val spec: ToolSpec,
        private val operation: (JSONObject) -> String,
    ) : Tool {
        override suspend fun invoke(argumentsJson: String): String = runCatching {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            operation(args)
        }.getOrElse { t ->
            // 一条工具调用出错不能掀翻整个回合，所以这里吞成错误结果交回模型。
            error("${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}")
        }
    }
}
