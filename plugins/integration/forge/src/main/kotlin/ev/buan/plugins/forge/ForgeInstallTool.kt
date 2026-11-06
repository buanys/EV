package ev.buan.plugins.forge

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import java.io.File
import java.security.MessageDigest
import org.cordis.api.PluginInstallRequest
import org.cordis.api.PluginInstallService
import org.cordis.api.PluginInstallStatus
import org.json.JSONObject

/**
 * 把 forge_build 的产物装进宿主。
 *
 * 为什么是 `dangerous = true`：这一颗插件会在**宿主进程里**、以宿主 UID 跑起来，
 * 能调 su、能读共享存储、能装下一个插件。安装等于授权任意代码执行，
 * 所以每一次都要人点头——门在 `ApprovalService`（`ev-ui` 的弹窗），不在这个类里。
 *
 * 由此带来的语义：**工具能被执行，就说明人已经对这次调用点了允许**。
 * 因此调用契约时 `approveUnsigned = true`，宿主据此把这份字节的哈希记进审批表
 * （之后字节被换掉仍然会在加载时被拒，那条防线不依赖人再点一次）。
 *
 * 工具本身仍然只做三件事：找产物、读产物、把结论原样翻译给模型。
 */
internal class ForgeInstallTool(
    private val root: File,
    /** 每次调用现查：宿主版本可能没有这条契约，插件也可能被停用。 */
    private val installLookup: () -> PluginInstallService?,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "forge_install",
        description = "把 forge_build 编出来的容器装进 EV 并启用。高危：插件运行在宿主进程内，" +
            "拥有与宿主相同的权限，所以**每次调用都需要用户批准**。安装前会做前置检查，" +
            "失败时返回可直接照做的原因。",
        parametersJson = """
            {"type":"object","properties":{
              "plugin_id":{"type":"string","description":"要安装的插件 id，必须已经用同名 id 编译过"},
              "note":{"type":"string","description":"一句话说明这个插件干什么、会用什么权限，显示给批准它的人看"}
            },"required":["plugin_id"],"additionalProperties":false}
        """.trimIndent(),
        dangerous = true,
    )

    override suspend fun invoke(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return errorJson("请求体不是合法 JSON")
        val pluginId = arguments.optString("plugin_id", "").trim()

        ForgeArtifacts.problem(root, pluginId)?.let { return errorJson(it) }

        val service = installLookup() ?: return errorJson(
            "宿主没有提供安装契约（PluginInstallService）：当前宿主版本不支持插件自安装",
        )

        val jarFile = ForgeArtifacts.jar(root, pluginId)
        val sidecarFile = ForgeArtifacts.sidecar(root, pluginId)
        val bytes = runCatching { jarFile.readBytes() }.getOrElse {
            return errorJson("读产物失败：${it.message ?: it.javaClass.simpleName}")
        }
        val sidecar = runCatching { sidecarFile.readText() }.getOrElse {
            return errorJson("读 sidecar 失败：${it.message ?: it.javaClass.simpleName}")
        }

        val result = service.install(
            PluginInstallRequest(bytes, sidecar, approveUnsigned = true),
        )

        return JSONObject()
            .put("status", result.status.name)
            .put("plugin_id", result.pluginId)
            .put("version", result.version ?: "")
            .put("sha256", result.sha256 ?: sha256Of(bytes))
            .put("active", result.active)
            .apply {
                result.code?.let { put("code", it) }
                result.message?.let { put("message", it) }
                put(
                    "next",
                    when (result.status) {
                        PluginInstallStatus.INSTALLED ->
                            if (result.active) "已在当前进程里跑起来，不需要重启。"
                            else "已装好但未激活：可能处于安全模式或该插件被停用，去插件管理页确认。"
                        PluginInstallStatus.NEEDS_APPROVAL ->
                            "宿主仍要求人工确认这份哈希：在插件管理页批准 $pluginId 后重试。"
                        PluginInstallStatus.REJECTED ->
                            "宿主拒绝了这次安装，按 message 的原因修改源码或声明后重新编译。"
                    },
                )
            }
            .toString()
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
