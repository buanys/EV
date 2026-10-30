package ev.buan.plugins.forge

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import ev.buan.plugins.forge.compiler.ForgeCompiler
import ev.buan.plugins.forge.compiler.ForgeRequest
import ev.buan.plugins.forge.compiler.ForgeResult
import java.io.File
import org.cordis.api.PluginInstallService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 给模型的一条工具：把一段 Kotlin 源码变成一个能装进 `mods/` 的插件容器。
 *
 * 只有一条，不拆成"写源码 / 编译 / 打包"三步：中间产物对模型没有意义，它要的是
 * "编译成没成、没成是为什么"。源码怎么写是 `file_write` 那种事，不在这里重复。
 *
 * 工具**不是** dangerous：它只读源码、只写自己的锻造目录，不碰宿主、不装任何东西。
 * 真正的越权点在安装（后续的 `forge_install`），那里的门必须单独存在。
 */
internal class ForgeBuildTool(
    /** 每次调用现查：工具链可能在本次会话中被用户停用。 */
    private val compilerLookup: () -> ForgeCompiler?,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "forge_build",
        description = "把一段 Kotlin 源码编译成 EV 插件容器（dex + sidecar），产物落在锻造目录。" +
            "编译失败时返回完整诊断（stage + diagnostics），据此修源码后重试。" +
            "只在本机编译，不安装任何东西。",
        parametersJson = """
            {"type":"object","properties":{
              "plugin_id":{"type":"string","description":"插件容器 id，至少含一个点，例如 taotie.forged"},
              "entry_class":{"type":"string","description":"实现 org.cordis.Plugin 的全限定类名"},
              "source":{"type":"string","description":"Kotlin 源码全文，必须含 entry_class 那个类"},
              "file_name":{"type":"string","description":"源文件名，默认按入口类命名，如 Forged.kt"},
              "permissions":{"type":"array","items":{"type":"string"},"description":"声明的宿主能力，如 PLUGIN_STORAGE"},
              "dependencies":{"type":"array","items":{"type":"string"},"description":"依赖的插件 id，决定加载顺序"}
            },"required":["plugin_id","entry_class","source"],"additionalProperties":false}
        """.trimIndent(),
        dangerous = false,
    )

    override suspend fun invoke(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return errorJson("请求体不是合法 JSON")

        val pluginId = arguments.optString("plugin_id", "").trim()
        val entryClass = arguments.optString("entry_class", "").trim()
        val source = arguments.optString("source", "")
        if (pluginId.isEmpty()) return errorJson("缺少 plugin_id")
        if (entryClass.isEmpty()) return errorJson("缺少 entry_class")
        if (source.isBlank()) return errorJson("缺少 source")

        val compiler = compilerLookup() ?: return errorJson(
            "锻造工具链未启用：请在插件管理里启用 ev.buan.plugins.forge.compiler 后重试",
        )

        val request = ForgeRequest(
            pluginId = pluginId,
            entryClass = entryClass,
            fileName = arguments.optString("file_name", "").takeIf { it.isNotBlank() }
                ?: (entryClass.substringAfterLast('.') + ".kt"),
            source = source,
            permissions = arguments.stringList("permissions"),
            dependencies = arguments.stringList("dependencies"),
        )

        return when (val result = compiler.compile(request)) {
            is ForgeResult.Ok -> JSONObject()
                .put("ok", true)
                .put("plugin_id", result.pluginId)
                .put("jar", result.jarPath)
                .put("jar_bytes", result.jarBytes)
                .put("dex_count", result.dexCount)
                .put("elapsed_ms", result.elapsedMs)
                .put("sidecar", result.sidecarJson)
                .put("next", "尚未安装。安装通道（forge_install）在 P3 接入；" +
                    "在此之前可以把 jar 与同名 sidecar 放进 /sdcard/EV/mods 后重启 EV。")
                .toString()

            is ForgeResult.Failed -> errorJson(
                "编译失败（阶段 ${result.stage.name}，${result.elapsedMs}ms）",
                result.diagnostics,
            )
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun errorJson(message: String, diagnostics: String? = null): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .apply { diagnostics?.let { put("diagnostics", it) } }
        .toString()
}

internal object ForgeTools {
    fun all(
        root: File,
        compilerLookup: () -> ForgeCompiler?,
        installLookup: () -> PluginInstallService?,
    ): List<Tool> = listOf(
        ForgeBuildTool(compilerLookup),
        ForgeInstallTool(root, installLookup),
    )
}
