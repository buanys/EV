package ev.buan.plugins.agent.device

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.ClipRead
import org.cordis.api.DeviceContextService
import org.cordis.api.LaunchOutcome
import org.cordis.api.NotifyOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备上下文工具：应用、状态、剪贴板、通知。
 *
 * 与 `StorageTools` 的分工：那组只碰**文件**，这组只碰**系统状态与几个受控副作用**。
 * 两者都需要宿主让出能力（`MANAGE_EXTERNAL_STORAGE` / `DeviceContextService`），所以同一个插件。
 *
 * 危险等级的划法（这是质量的一部分，不是装饰）：
 *  - 读类（app_list / device_status / clip_read）：不需要门，它们不改任何东西；
 *  - 可见但可逆（app_launch / clip_write / notify_send）：不做审批门，但**结果如实回传**，
 *    模型能知道"我刚刚把你切到了微信"；
 *  - 不可逆或能脱离视野的（run_shell、写文件）：那才是 dangerous，在别的插件里。
 */
internal object SystemTools {

    fun all(service: () -> DeviceContextService?): List<Tool> = listOf(
        AppListTool(service),
        AppLaunchTool(service),
        DeviceStatusTool(service),
        ClipReadTool(service),
        ClipWriteTool(service),
        NotifyTool(service),
    )

    private fun missingService(): String =
        "设备上下文未启用：宿主没有发布 DeviceContextService（宿主版本过旧或该服务未提供）"

    // ---------------------------------------------------------------- 应用

    private class AppListTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "app_list",
            description = "列出手机上有启动入口的应用（名称 + 包名）。用 filter 缩小范围——" +
                "全量列表很长，先按关键词查再启动。只列可启动的应用，不含服务和纯库。",
            parametersJson = """
                {"type":"object","properties":{
                  "filter":{"type":"string","description":"按名称或包名做不区分大小写的子串过滤，例如 微信 / music / com.tencent"},
                  "limit":{"type":"integer","description":"最多返回多少条，默认 40，上限 200"},
                  "include_system":{"type":"boolean","description":"是否包含系统预装应用，默认 false"}
                },"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
            val filter = args.optString("filter", "").trim()
            val limit = args.optInt("limit", 40).coerceIn(1, 200)
            val includeSystem = args.optBoolean("include_system", false)

            val apps = service.apps()
            val matched = apps.asSequence()
                .filter { includeSystem || !it.system }
                .filter {
                    filter.isEmpty() ||
                        it.label.contains(filter, ignoreCase = true) ||
                        it.packageName.contains(filter, ignoreCase = true)
                }
                .toList()

            return JSONObject()
                .put("total", apps.size)
                .put("matched", matched.size)
                .put("returned", minOf(limit, matched.size))
                .put(
                    "apps",
                    JSONArray().apply {
                        matched.take(limit).forEach {
                            put(JSONObject().put("label", it.label).put("package", it.packageName).put("system", it.system))
                        }
                    },
                )
                // 截断就说清楚怎么接着查，否则模型会把"只看到 40 个"当成"只有 40 个"。
                .apply {
                    if (matched.size > limit) {
                        put("hint", "还有 ${matched.size - limit} 个没返回：用 filter 缩小范围，或调大 limit")
                    }
                }
                .toString()
        }
    }

    private class AppLaunchTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "app_launch",
            description = "打开一个应用的前台界面。需要包名（先用 app_list 查）。" +
                "只能打开有启动入口的应用；服务、被禁用的应用会明确报错，不会静默失败。",
            parametersJson = """
                {"type":"object","properties":{
                  "package":{"type":"string","description":"应用包名，例如 com.tencent.mm"}
                },"required":["package"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            val packageName = runCatching { JSONObject(argumentsJson).optString("package", "").trim() }
                .getOrDefault("")
            if (packageName.isEmpty()) return error("缺少 package")

            return when (val outcome = service.launch(packageName)) {
                LaunchOutcome.Launched -> JSONObject().put("ok", true).put("launched", packageName).toString()
                is LaunchOutcome.NoSuchApp -> error("没有这个应用：${outcome.packageName}（用 app_list 查准确的包名）")
                is LaunchOutcome.NoLauncher -> error("${outcome.packageName} 存在但没有启动入口（服务或被禁用）")
                is LaunchOutcome.Blocked -> error("系统拒绝了这次启动：${outcome.reason}")
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    private class DeviceStatusTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "device_status",
            description = "一次读全设备状态：机型/系统版本、电量与充电、存储与内存余量、网络类型、" +
                "屏幕是否亮、开机时长。判断「能不能装东西 / 能不能联网 / 是不是快没电了」先看这个。",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            val s = service.status()
            return JSONObject()
                .put("model", s.model)
                .put("manufacturer", s.manufacturer)
                .put("android", s.androidRelease)
                .put("sdk", s.sdkInt)
                .put("battery_percent", s.batteryPercent)
                .put("charging", s.charging)
                .put("storage_total_bytes", s.storageTotalBytes)
                .put("storage_free_bytes", s.storageFreeBytes)
                .put("memory_total_bytes", s.memoryTotalBytes)
                .put("memory_available_bytes", s.memoryAvailableBytes)
                .put("network", s.network)
                .put("screen_on", s.screenOn ?: JSONObject.NULL)
                .put("uptime_ms", s.uptimeMs)
                .toString()
        }
    }

    // ---------------------------------------------------------------- 剪贴板

    private class ClipReadTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "clip_read",
            description = "读系统剪贴板里的文本，最长返回 8000 字符。注意：Android 只允许前台应用读剪贴板，" +
                "EV 不在前台时会明确回复读不到（而不是说剪贴板是空的）。",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            return when (val clip = service.clipboard()) {
                is ClipRead.Content -> JSONObject()
                    .put("ok", true)
                    .put("text", clip.text.take(MAX_CLIP_CHARS))
                    .put("truncated", clip.text.length > MAX_CLIP_CHARS)
                    .toString()
                ClipRead.Empty -> JSONObject().put("ok", true).put("text", "").put("empty", true).toString()
                ClipRead.NotForeground -> error("读不到剪贴板：Android 只允许前台应用读取，请先把 EV 切到前台")
            }
        }
    }

    private class ClipWriteTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "clip_write",
            description = "把一段文本写入系统剪贴板（会覆盖用户当前复制的内容）。",
            parametersJson = """
                {"type":"object","properties":{
                  "text":{"type":"string","description":"要写入的文本"}
                },"required":["text"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            val text = runCatching { JSONObject(argumentsJson).optString("text", "") }.getOrDefault("")
            if (text.isEmpty()) return error("text 不能为空")
            return if (service.setClipboard(text)) {
                JSONObject().put("ok", true).put("chars", text.length).toString()
            } else {
                error("写剪贴板失败：个别 ROM 在应用没有输入焦点时会拒绝")
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    private class NotifyTool(private val service: () -> DeviceContextService?) : Tool {
        override val spec = ToolSpec(
            name = "notify_send",
            description = "给用户发一条系统通知，用于长任务完成或需要他注意的时候。需要通知权限；" +
                "没授权会明确回复 NoPermission，不会静默丢弃。",
            parametersJson = """
                {"type":"object","properties":{
                  "title":{"type":"string","description":"通知标题"},
                  "body":{"type":"string","description":"通知正文，尽量一行说清"}
                },"required":["title","body"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val service = service() ?: return error(missingService())
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return error("请求体不是合法 JSON")
            val title = args.optString("title", "").trim()
            val body = args.optString("body", "").trim()
            if (title.isEmpty() || body.isEmpty()) return error("title 与 body 都不能为空")

            return when (val outcome = service.notify(title, body)) {
                NotifyOutcome.Posted -> JSONObject().put("ok", true).put("posted", true).toString()
                NotifyOutcome.NoPermission -> error("没有通知权限：请在系统设置里给 EV 打开通知")
                is NotifyOutcome.Failed -> error("发通知失败：${outcome.reason}")
            }
        }
    }

    private fun error(message: String): String = JSONObject().put("ok", false).put("error", message).toString()

    private const val MAX_CLIP_CHARS = 8_000
}
