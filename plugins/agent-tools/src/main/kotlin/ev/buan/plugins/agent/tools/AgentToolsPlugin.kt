package ev.buan.plugins.agent.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentPage
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
 *
 * It also owns the tools panel: the plugin that publishes the registry is the one that can show it
 * without importing somebody else's data shape.
 */
class AgentToolsPlugin :
    Plugin<Unit>,
    AgentSurface,
    AgentPage,
    org.cordis.api.ManifestProvider {
    override val name = "agent-tools"
    override val title = "agent 工具"
    override val body = "工具总线 + 受控文件工具（只读写本插件存储区），发布为 ToolRegistry。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, PluginStorage.Key)

    /** Set during apply; the page is only reachable after activation, so it is always populated. */
    private var registry: ToolRegistry? = null

    override val pageTitle: String = "工具"

    /** Opened from a menu, never the app root. */
    override val isHomeCandidate: Boolean = false

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) {
        ToolsPage(registry = registry, onBack = onBack, modifier = modifier)
    }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val storage = ctx.require(PluginStorage.Key)
        val bus = ToolRegistryBus()
        bus.register(PLUGIN_ID, StorageTools.all(storage))
        registry = bus
        directory.publish(ToolRegistry.Key, bus)
        // Disabling the plugin retracts the capability in the same step that disposes it, so a
        // consumer never keeps tools whose storage access is already gone. The identity check keeps
        // this stale disposer from retracting a newer instance published after a re-activation.
        effect.collect {
            if (directory.lookup(ToolRegistry.Key) === bus) directory.retract(ToolRegistry.Key)
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.tools"
    }

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.tools",
        name = "文件工具",
        version = "1.1.0",
        description = "读写、列目录、删除文件。模型改代码用的就是这组工具。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("file_read", org.cordis.api.PluginCapability.Kind.TOOL, "读文件", "按行读取，带行号。"),
            org.cordis.api.PluginCapability("file_list", org.cordis.api.PluginCapability.Kind.TOOL, "列目录", "列出目录内容与大小。"),
            org.cordis.api.PluginCapability("file_write", org.cordis.api.PluginCapability.Kind.TOOL, "写文件", "整文件写入，走原子替换。"),
            org.cordis.api.PluginCapability("file_delete", org.cordis.api.PluginCapability.Kind.TOOL, "删除文件", "删除文件或目录。"),
        ),
    )
}
