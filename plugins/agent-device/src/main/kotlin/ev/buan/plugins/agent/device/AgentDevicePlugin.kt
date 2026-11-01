package ev.buan.plugins.agent.device

import android.os.Environment
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.DeviceContextService
import org.cordis.api.ScreenService
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
class AgentDevicePlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    override val name = "agent-device"
    override val title = "设备文件"
    override val body = "让 agent 读手机共享存储，并感知系统：应用列表与启动、设备状态、剪贴板、通知。"

    // DeviceContextService 声明在 inject 里，才允许用 ctx.get 做**可选**查询：
    // 宿主版本旧时它是 null，插件照常起，只是那几个工具如实回答"未启用"。
    override val inject: Dependencies = dependencies(
        ServiceDirectory.Key,
        DeviceContextService.Key,
        ScreenService.Key,
    )

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // 工具总线由 agent-tools 发布。sidecar 声明了依赖顺序，但它仍是**另一个插件的 apply()**：
        // 两边同时启动时，这里可能先跑完。早退会让整组工具凭空消失，而且没有任何痕迹——
        // 所以短轮询再放弃，放弃时也要留下日志。
        val registry = awaitRegistry(directory)
        if (registry == null) {
            android.util.Log.w("EV", "agent-device: 等不到 ToolRegistry，本插件没有注册任何工具")
            return
        }

        val root = Environment.getExternalStorageDirectory() ?: File("/sdcard")
        val files = DeviceFiles(root)
        val tools = DeviceTools.all(files, DeviceSearch(files, root)) +
            WriteTools.all(files) +
            SystemTools.all { ctx.get(DeviceContextService.Key) } +
            ScreenTools.all { ctx.get(ScreenService.Key) }
        registry.register(PLUGIN_ID, tools)
        android.util.Log.i("EV", "agent-device: 注册 ${tools.size} 个工具（${tools.joinToString(",") { it.spec.name }}）")
        effect.collect {
            registry.unregister(PLUGIN_ID)
            android.util.Log.i("EV", "agent-device: 已撤回工具注册")
        }
    }

    /** 最多等 3 秒：插件启动是并发的，工具总线可能晚一步就位。 */
    private suspend fun awaitRegistry(directory: ServiceDirectory): ToolRegistry? {
        repeat(AWAIT_ATTEMPTS) {
            directory.lookup(ToolRegistry.Key)?.let { return it }
            kotlinx.coroutines.delay(AWAIT_INTERVAL_MS)
        }
        return directory.lookup(ToolRegistry.Key)
    }


    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.device"
        const val AWAIT_ATTEMPTS = 30
        const val AWAIT_INTERVAL_MS = 100L
    }

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.device",
        name = "设备能力",
        version = "0.1.0",
        description = "查看和操作这台手机：应用列表、启动应用、读屏、截屏。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("app_list", org.cordis.api.PluginCapability.Kind.TOOL, "列出应用"),
            org.cordis.api.PluginCapability("app_launch", org.cordis.api.PluginCapability.Kind.TOOL, "启动应用"),
            org.cordis.api.PluginCapability("screen", org.cordis.api.PluginCapability.Kind.TOOL, "读取屏幕"),
        ),
        permissions = listOf("android.permission.INTERNET"),
    )

}
