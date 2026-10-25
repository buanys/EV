package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
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
class AgentBrainPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-brain"
    override val title = "对话大脑"
    override val body = "组合 agent-model / agent-tools / agent-memory；缺失的依赖按未连接或纯文本降级。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val service = AgentBrainService(
            capabilities = {
                BrainCapabilities(
                model = directory.lookup(ModelService.Key),
                tools = directory.lookup(ToolRegistry.Key),
                memory = directory.lookup(MemoryService.Key),
                    approval = directory.lookup(ApprovalService.Key),
                )
            },
        )
        directory.publish(AgentService.Key, service)
        // The identity check keeps this stale disposer from retracting a newer instance published
        // after a re-activation, mirroring the tools/memory plugins.
        effect.collect {
            if (directory.lookup(AgentService.Key) === service) directory.retract(AgentService.Key)
        }
    }
}
