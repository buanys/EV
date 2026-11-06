package ev.buan.plugins.agent.phone

import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.CalendarService
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 日历能力：把宿主独占的 `CalendarContract` 变成 agent 能用的七个工具。
 *
 * 为什么单独一个插件而不是塞进 `agent-device`：那是"共享存储上的文件"，这是"我的日程"。
 * 两者要的授权不一样（`MANAGE_EXTERNAL_STORAGE` vs `READ_WRITE_CALENDAR`），混在一起
 * 用户就没有办法"给你看文件但不给你动日历"。
 *
 * 依赖声明里写 `CalendarService.Key` 是刻意的：没在元数据里声明 `CALENDAR` 的插件根本
 * 拿不到绑定，激活会停在 PENDING 而不是安静地少几个工具。
 */
class AgentPhonePlugin : Plugin<Unit>, AgentSurface {

    override val name = "agent-phone"
    override val title = "日历"
    override val body = "读写你的日程：查今天有什么会、建提醒、改时间、删日程。"

    override val inject: Dependencies = dependencies(
        ServiceDirectory.Key,
        CalendarService.Key,
    )

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val calendar = ctx.require(CalendarService.Key)
        val directory = ctx.require(ServiceDirectory.Key)
        // 工具总线由 agent-tools 发布；两边同时启动时它可能晚一步就位。
        val registry = awaitRegistry(directory)
        if (registry == null) {
            android.util.Log.w("EV", "agent-phone: 等不到 ToolRegistry，本插件没有注册任何工具")
            return
        }
        // 日历走宿主契约（ContentResolver + 运行时权限），闹钟/便签走 root provider；
        // 两类都是"手机上的个人数据"，所以在一个插件里，而不是两个。
        val tools = CalendarTool.all(calendar) + RootPhoneTools.all()
        registry.register(PLUGIN_ID, tools)
        android.util.Log.i("EV", "agent-phone: 注册 ${tools.size} 个工具（${tools.joinToString(",") { it.spec.name }}）")
        effect.collect {
            registry.unregister(PLUGIN_ID)
            android.util.Log.i("EV", "agent-phone: 已撤回工具注册")
        }
    }

    /** 最多等 3 秒，理由与 agent-device 相同：早退会让整组工具凭空消失且没有痕迹。 */
    private suspend fun awaitRegistry(directory: ServiceDirectory): ToolRegistry? {
        repeat(AWAIT_ATTEMPTS) {
            directory.lookup(ToolRegistry.Key)?.let { return it }
            kotlinx.coroutines.delay(AWAIT_INTERVAL_MS)
        }
        return directory.lookup(ToolRegistry.Key)
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.phone"
        const val AWAIT_ATTEMPTS = 30
        const val AWAIT_INTERVAL_MS = 100L
    }
}
