package ev.buan.plugins.agent.tools

import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * Publishes the tool bus as the single [ToolRegistry] into the host's [ServiceDirectory].
 *
 * A Cordis service would make every consumer a hard dependency and leave it waiting while this
 * plugin is disabled; the directory is a lookup, so an agent simply runs without tools instead.
 *
 * This plugin owns the bus and seeds it with its own controlled file tools; other capability
 * plugins (agent-shell, device-context, ...) look the registry up and [ToolRegistry.register]
 * their tools under their own plugin id instead of publishing a second registry. Such plugins
 * should declare a sidecar `dependencies` entry on this plugin, so the bus exists before they
 * activate and outlives them on shutdown.
 */
class AgentToolsPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-tools"
    override val title = "agent 工具"
    override val body = "工具总线 + 受控文件工具（只读写本插件存储区），发布为 ToolRegistry。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, PluginStorage.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val storage = ctx.require(PluginStorage.Key)
        val registry = ToolRegistryBus()
        registry.register(PLUGIN_ID, StorageTools.all(storage))
        directory.publish(ToolRegistry.Key, registry)
        // Disabling the plugin retracts the capability in the same step that disposes it, so a
        // consumer never keeps tools whose storage access is already gone. The identity check keeps
        // this stale disposer from retracting a newer instance published after a re-activation.
        effect.collect {
            if (directory.lookup(ToolRegistry.Key) === registry) directory.retract(ToolRegistry.Key)
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.tools"
    }
}
