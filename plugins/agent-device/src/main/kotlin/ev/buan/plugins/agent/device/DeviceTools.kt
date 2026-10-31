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

    fun all(files: DeviceFiles, search: DeviceSearch): List<Tool> = listOf(
        DeviceTool(LIST_SPEC) { args -> list(files, args) },
        DeviceTool(READ_SPEC) { args -> read(files, args) },
        DeviceTool(FIND_SPEC) { args -> find(search, args) },

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

    private fun find(search: DeviceSearch, args: JSONObject): String {
        val query = args.optString("query", "").trim()
        val kind = args.optString("kind", "both").lowercase().let {
            if (it in setOf("name", "content", "both")) it else "both"
        }
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val maxDepth = args.optInt("max_depth", 4).coerceIn(0, 8)
        val includeHidden = args.optBoolean("include_hidden", false)

        if (query.isEmpty() && kind == "content") {
            return error("按内容搜索必须给 query")
        }
        return search.toJson(search.search(query, kind, limit, maxDepth, includeHidden), query, kind)
    }

    private val FIND_SPEC = ToolSpec(
        name = "device_find",
        description = "在共享存储里按文件名或文件内容搜索。比逐层 device_list 快得多，" +
            "找「上次那个笔记」「包含某句话的文件」用它。" +
            "content 搜索只读前 256 KB 且跳过二进制；结果太多会明确说「没搜完」和原因。",
        parametersJson = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"要找的字符串；留空则列出（配合 kind=name 相当于带过滤的 find）"},
              "kind":{"type":"string","enum":["name","content","both"],"description":"搜文件名、文件内容还是两者，默认 both"},
              "limit":{"type":"integer","description":"最多返回多少条，默认 50，上限 200"},
              "max_depth":{"type":"integer","description":"目录深度上限，默认 4，最大 8"},
              "include_hidden":{"type":"boolean","description":"是否包含 . 开头的隐藏项，默认 false"}
            },"required":["query"],"additionalProperties":false}
        """.trimIndent(),
    )

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
