package ev.buan.plugins.agent.device

import android.os.Environment
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import java.io.File

/**
 * 设备文件能力：把宿主的共享存储访问变成 agent 的工具。
 *
 * 为什么是独立插件而不是塞进 `agent-tools`：`agent-tools` 的四个工具走 [org.cordis.api.PluginStorage]，
 * 作用域是**插件自己的沙箱**；这里的工具走宿主的 `MANAGE_EXTERNAL_STORAGE`，作用域是整块共享存储。
 * 两种权限模型混在一个插件里，早晚会有人以为"read 能读 Download"。
 *
 * 拿不到 Android `Context`（插件只能从 ServiceDirectory 取服务），但这里也不需要——
 * [Environment] 的静态方法就能给出共享存储根，`isExternalStorageManager()` 还能判断权限是否真的给了。
 * 权限没给时工具照注册，调用会返回一条明确的原因：让 agent 知道"是没授权"，而不是"文件不存在"。
 */
class AgentDevicePlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-device"
    override val title = "设备文件"
    override val body = "让 agent 读写手机共享存储（只读）：Download、Documents、Pictures。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // 工具总线由 agent-tools 发布，sidecar 已声明依赖，所以这里通常已经就绪。
        val registry = directory.lookup(ToolRegistry.Key) ?: return

        val root = Environment.getExternalStorageDirectory() ?: File("/sdcard")
        registry.register(PLUGIN_ID, DeviceTools.all(DeviceFiles(root)))
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.device"
    }
}
