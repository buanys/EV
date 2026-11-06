package ev.buan.plugins.probe.remote

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 受限档的验证夹具：一个工具，回答自己的 pid。
 *
 * 除了注册工具什么都不做——不碰存储、不碰凭据、不要 UI。这正是 `RestrictedProfile` 允许
 * 进子进程的那一类，所以它应当出现在 `:plugin` 进程里；如果它跟宿主同一个 pid，
 * 说明隔离没生效。
 */
class ProbeRemotePlugin : Plugin<Unit> {

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // 与真实插件同一条路径：总线由 agent-tools 发布，在子进程里由宿主提供的那一份顶上。
        val registry = directory.lookup(ToolRegistry.Key) ?: return
        registry.register(PLUGIN_ID, listOf(PingTool()))
        effect.collect { registry.unregister(PLUGIN_ID) }
    }

    private class PingTool : Tool {
        override val spec = ToolSpec(
            name = "remote_ping",
            description = "返回执行这个工具的进程 pid，用来确认受限插件跑在子进程里",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        )

        override suspend fun invoke(argumentsJson: String): String =
            "pong pid=${android.os.Process.myPid()} args=$argumentsJson"
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.probe.remote"
    }
}
