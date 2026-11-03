package ev.buan.plugins.agent.gui

import android.util.Log
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.ScreenService
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 屏幕能力的独立插件：看、点、滑、输入、截图。
 *
 * 为什么从 `agent-device` 里拆出来：那一组工具的数据来源是宿主的 `AccessibilityService`，
 * 权限模型、失败方式、审批理由都和"读写文件"完全不同——它摸的是**其他应用的界面**。
 * 混在一个插件里，关掉屏幕能力就得把文件工具一起关掉，或者对着一个 25 项的列表猜哪几项要审。
 *
 * 依赖是**可选**的：`ScreenService` 没发布（宿主过旧）时插件照常启动，工具注册齐全，
 * 调用时如实回答"屏幕服务未启用"，而不是整组消失让模型以为没有这个能力。
 */
class AgentGuiPlugin : Plugin<Unit>, AgentSurface, ManifestProvider {

    override val name = "agent-gui"
    override val title = "屏幕操作"
    override val body =
        "让 agent 看见并操作界面：读屏、点按、滑动、输入、截图。" +
            "依赖系统的无障碍授权，没有它这组工具只会如实报错。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, ScreenService.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // 工具总线由 agent-tools 发布。插件启动是并发的，这里可能先跑完；
        // 早退会让整组工具凭空消失且没有痕迹，所以短轮询再放弃，放弃也要留日志。
        val registry = awaitRegistry(directory)
        if (registry == null) {
            Log.w("EV", "agent-gui: 等不到 ToolRegistry，本插件没有注册任何工具")
            return
        }

        val tools = GuiTools.all { ctx.get(ScreenService.Key) } + GuiVision.all()
        registry.register(PLUGIN_ID, tools)
        Log.i("EV", "agent-gui: 注册 ${tools.size} 个工具（${tools.joinToString(",") { it.spec.name }}）")
        effect.collect {
            registry.unregister(PLUGIN_ID)
            Log.i("EV", "agent-gui: 已撤回工具注册")
        }
    }

    /** 最多等 3 秒：与 agent-device 同一套约定。 */
    private suspend fun awaitRegistry(directory: ServiceDirectory): ToolRegistry? {
        repeat(AWAIT_ATTEMPTS) {
            directory.lookup(ToolRegistry.Key)?.let { return it }
            kotlinx.coroutines.delay(AWAIT_INTERVAL_MS)
        }
        return directory.lookup(ToolRegistry.Key)
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.gui"
        const val AWAIT_ATTEMPTS = 30
        const val AWAIT_INTERVAL_MS = 100L
    }

    override val manifest: PluginManifest = PluginManifest(
        id = PLUGIN_ID,
        name = "屏幕操作",
        version = "0.1.0",
        description = "看屏幕、点、滑、输入、截图。全部通过宿主的无障碍服务，需要系统授权。",
        capabilities = listOf(
            PluginCapability("screen_dump", PluginCapability.Kind.TOOL, "读屏", "压平的无障碍节点树。"),
            PluginCapability("screen_tap", PluginCapability.Kind.TOOL, "点击或长按", "按序号定位，点击前核对。"),
            PluginCapability("screen_tap_text", PluginCapability.Kind.TOOL, "按文字点击"),
            PluginCapability("screen_input", PluginCapability.Kind.TOOL, "输入文本"),
            PluginCapability("screen_swipe", PluginCapability.Kind.TOOL, "按坐标滑动"),
            PluginCapability("screen_scroll", PluginCapability.Kind.TOOL, "按方向滚动", "自动计算手势。"),
            PluginCapability("screen_wait", PluginCapability.Kind.TOOL, "等界面变化", "只读轮询。"),
            PluginCapability("screen_read_all", PluginCapability.Kind.TOOL, "滚动读全文", "翻页采集并去重。"),
            PluginCapability("screen_key", PluginCapability.Kind.TOOL, "系统键"),
            PluginCapability("screen_capture", PluginCapability.Kind.TOOL, "截屏", "root，需审批。"),
            PluginCapability("read_image", PluginCapability.Kind.TOOL, "看图", "把图片交给多模态模型。"),
        ),
        permissions = listOf("android.permission.INTERNET"),
    )
}
