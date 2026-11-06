package ev.buan.plugins.agent.device

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONObject

/**
 * 共享存储的**写**能力。
 *
 * 为什么和读分开：读错只浪费一轮对话，写错会毁掉用户的东西。所以这组工具在参数上做了三件事——
 *  - 覆盖必须显式（`overwrite=true`），默认只在文件不存在时创建；
 *  - 单次写入有字节上限，超了就让它分段，而不是悄悄截断；
 *  - 移动与删除标 `dangerous`，走宿主的审批门（`ev-ui` 的弹窗），这是唯一不可逆的两个。
 *
 * 边界沿用 [DeviceFiles]：共享存储根之内，且 `Android/` 子树一律拒绝（系统托管）。
 */
internal object WriteTools {

    fun all(files: DeviceFiles): List<Tool> = listOf(
        WriteTool(files),
        MkdirTool(files),
        MoveTool(files),
        DeleteTool(files),
    )

    private class WriteTool(private val files: DeviceFiles) : Tool {
        override val spec = ToolSpec(
            name = "device_write",
            description = "把文本写进手机共享存储（/sdcard 下）。默认**不覆盖**已存在的文件：" +
                "要么换路径，要么显式 overwrite=true。append=true 时追加到末尾。" +
                "单次最多 1 MB，更大的内容请分段写。",
            parametersJson = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"目标路径，绝对路径或相对共享存储根，例如 Download/notes.md"},
                  "content":{"type":"string","description":"要写入的文本"},
                  "append":{"type":"boolean","description":"追加而不是覆盖，默认 false"},
                  "overwrite":{"type":"boolean","description":"允许覆盖已存在的文件，默认 false"}
                },"required":["path","content"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
                ?: return error("请求体不是合法 JSON")
            val path = args.optString("path", "").trim()
            if (path.isEmpty()) return error("缺少 path")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val overwrite = args.optBoolean("overwrite", false)

            // 覆盖是显式同意，不是默认：猜错一次路径就会冲掉用户的东西，而且没有回收站。
            if (!append && !overwrite && files.exists(path)) {
                return error("$path 已存在。要覆盖就传 overwrite=true；要追加就传 append=true")
            }

            return when (val result = files.writeText(path, content, append)) {
                is DeviceFiles.Result.Ok -> JSONObject()
                    .put("ok", true)
                    .put("bytes", result.value)
                    .put("appended", append)
                    .toString()
                is DeviceFiles.Result.Fail -> error(result.error)
            }
        }
    }

    private class MkdirTool(private val files: DeviceFiles) : Tool {
        override val spec = ToolSpec(
            name = "device_mkdir",
            description = "在共享存储里创建目录（含父目录）。已存在时视为成功。",
            parametersJson = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"目录路径，例如 Download/ev-notes"}
                },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val path = runCatching { JSONObject(argumentsJson).optString("path", "").trim() }.getOrDefault("")
            if (path.isEmpty()) return error("缺少 path")
            return when (val result = files.mkdir(path)) {
                is DeviceFiles.Result.Ok -> JSONObject().put("ok", true).put("path", result.value).toString()
                is DeviceFiles.Result.Fail -> error(result.error)
            }
        }
    }

    private class MoveTool(private val files: DeviceFiles) : Tool {
        override val spec = ToolSpec(
            name = "device_move",
            description = "移动或重命名共享存储里的文件/目录。目标已存在时拒绝（不覆盖）。" +
                "高危：会改变用户文件的位置。",
            parametersJson = """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"源路径"},
                  "to":{"type":"string","description":"目标路径（含新文件名）"}
                },"required":["from","to"],"additionalProperties":false}
            """.trimIndent(),
            dangerous = true,
        )

        override suspend fun invoke(argumentsJson: String): String {
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
                ?: return error("请求体不是合法 JSON")
            val from = args.optString("from", "").trim()
            val to = args.optString("to", "").trim()
            if (from.isEmpty() || to.isEmpty()) return error("from 与 to 都不能为空")
            return when (val result = files.move(from, to)) {
                is DeviceFiles.Result.Ok -> JSONObject().put("ok", true).put("path", result.value).toString()
                is DeviceFiles.Result.Fail -> error(result.error)
            }
        }
    }

    private class DeleteTool(private val files: DeviceFiles) : Tool {
        override val spec = ToolSpec(
            name = "device_delete",
            description = "删除共享存储里的文件或整个目录（目录递归删除）。**不可恢复**，所以每次都要人工批准。" +
                "Android/ 子树与共享存储根拒绝删除。",
            parametersJson = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"要删除的路径"}
                },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
            dangerous = true,
        )

        override suspend fun invoke(argumentsJson: String): String {
            val path = runCatching { JSONObject(argumentsJson).optString("path", "").trim() }.getOrDefault("")
            if (path.isEmpty()) return error("缺少 path")
            return when (val result = files.delete(path)) {
                is DeviceFiles.Result.Ok -> JSONObject().put("ok", true).put("deleted", result.value).toString()
                is DeviceFiles.Result.Fail -> error(result.error)
            }
        }
    }
    /** 存在性检查走 DeviceFiles 的读取路径，避免在两处各写一套路径解析。 */

    private fun error(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
