package ev.buan.plugins.agent.prompt

import ev.buan.plugins.agent.api.PromptContext
import ev.buan.plugins.agent.api.PromptLayer
import ev.buan.plugins.agent.api.SystemPromptService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * Publishes the agent's behaviour baseline as [SystemPromptService].
 *
 * A directory service, not a Cordis dependency: the brain looks it up per request, so disabling
 * this plugin degrades the agent to the brain's own built-in baseline instead of leaving the chat
 * page waiting on a service that will never arrive.
 *
 * The layers themselves live in [PromptLayers] as a pure function, which is what the unit test
 * pins; this class only decides when they are published.
 */
class AgentPromptPlugin : Plugin<Unit>, AgentSurface, ManifestProvider {

    override val name = "agent-prompt"
    override val title = "系统提示词"
    override val body =
        "给 agent 一套行为基线：身份、什么时候停、工具怎么用、怎么说话。缺了它，主循环回落到内置的一小段。"

    override val manifest: PluginManifest = PluginManifest(
        id = PLUGIN_ID,
        name = "系统提示词",
        version = "0.1.0",
        description = "分层拼装 agent 的行为基线，可被替换或再加一份做对照。",
        capabilities = listOf(
            PluginCapability(
                id = "agent/system-prompt",
                kind = PluginCapability.Kind.SERVICE,
                title = "行为基线",
                description = "按优先级给出若干层提示词；主循环超预算时从最小的一层开始丢。",
            ),
        ),
        permissions = listOf(),
    )

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val service = PromptServiceImpl()
        directory.publish(SystemPromptService.Key, service)
        // Identity check, like the other agent plugins: a stale disposer must not retract a newer
        // instance published after a re-activation.
        effect.collect {
            if (directory.lookup(SystemPromptService.Key) === service) {
                directory.retract(SystemPromptService.Key)
            }
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.prompt"
    }
}

/**
 * The published service.
 *
 * Stateless on purpose: `layers` has to be synchronous and deterministic, so nothing here reads
 * disk or settings. User-customised text belongs to a settings pass, not to activation.
 */
private class PromptServiceImpl : SystemPromptService {
    override fun layers(ctx: PromptContext): List<PromptLayer> = PromptLayers.all(ctx, null)
}
