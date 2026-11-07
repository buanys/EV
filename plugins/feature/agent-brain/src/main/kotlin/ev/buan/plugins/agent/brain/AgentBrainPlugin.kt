package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolGuardRegistry
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * Publishes the conversation brain as an [AgentService] into the host's [ServiceDirectory].
 *
 * The brain owns no host capability of its own: it only composes what other plugins publish, so it
 * declares no permissions. The lookups happen per call (see [CapabilityProvider]) and the directory
 * retract on dispose keeps a disabled brain from leaving a stale service behind.
 */
class AgentBrainPlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.brain",
        name = "Agent 主循环",
        version = "1.0.4",
        description = "把对话变成行动：规划、调工具、收尾，带预算与进度事件。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("agent/service", org.cordis.api.PluginCapability.Kind.SERVICE, "运行一轮对话", "接收消息与工具清单，产出回复与事件流。"),
        ),
    )

    override val name = "agent-brain"
    override val title = "对话大脑"
    override val body = "组合 agent-model / agent-tools / agent-memory；缺失的依赖按未连接或纯文本降级。"

    // HudService 走 host 的 cordis.provide 发布，不在 ServiceDirectory 里，所以要用 ctx.get
    // 取；声明在 inject 里才允许做这种可选查询，宿主旧版本时它是 null。
    override val inject: Dependencies = dependencies(ServiceDirectory.Key, org.cordis.api.HudService.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // guard 注册表由 brain 发布：它才是每次工具调用真正会去问的人。
        // 其它插件 lookup 之后往上挂，卸载时调撤销函数——注册表本身不缓存任何判断结果。
        val guards = ToolGuards()
        val service = AgentBrainService(
            capabilities = {
                BrainCapabilities(
                    model = directory.lookup(ModelService.Key),
                    tools = directory.lookup(ToolRegistry.Key),
                    memory = directory.lookup(MemoryService.Key),
                    approval = directory.lookup(ApprovalService.Key),
                    prompt = directory.lookup(SystemPromptService.Key),
                    hud = ctx.get(org.cordis.api.HudService.Key),
                    // 事件流是历史真相源；没装 agent-log 时退回 memory（老会话）。
                    sessionLog = directory.lookup(SessionLog.Key),
                    // 没有 policy 时一律 ASK，行为与 policy 出现之前完全一致。
                    policy = directory.lookup(ApprovalPolicy.Key),
                )
            },
            guards = guards,
        )
        directory.publish(AgentService.Key, service)
        directory.publish(ToolGuardRegistry.Key, guards)
        // 胶囊上的两个按钮交给 brain：只有它知道暂停该停在哪儿、停止该怎么收尾。
        runCatching { ctx.get(org.cordis.api.HudService.Key)?.attachController(service) }
        // The identity check keeps this stale disposer from retracting a newer instance published
        // after a re-activation, mirroring the tools/memory plugins.
        effect.collect {
            if (directory.lookup(AgentService.Key) === service) directory.retract(AgentService.Key)
            if (directory.lookup(ToolGuardRegistry.Key) === guards) directory.retract(ToolGuardRegistry.Key)
        }
    }
}
