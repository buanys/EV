package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * OpenAI-compatible multi-provider model plugin, shipped as a jar.
 *
 * It publishes two capabilities into the host's [ServiceDirectory]:
 *  - [ModelService] (`agent/model`) for completion consumers such as the agent brain;
 *  - [ModelProviderService] (`agent/model-providers`) for the settings UI that manages providers.
 *
 * Both are directory entries rather than Cordis services: a disabled plugin must read as "no model
 * connected", not as a dependency that leaves consumers waiting.
 *
 * Configuration lives in this plugin's [PluginStorage] area (`config/providers.json`, with the old
 * `config/model.json` migrated on first read). Capabilities used: `NETWORK` for HTTP calls and
 * `PLUGIN_STORAGE` for the document - both declared in the container metadata, so activation is
 * gated until the user grants them (ADR-0003).
 */
class AgentModelPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-model"
    override val title = "模型服务"
    override val body = "OpenAI 兼容的多提供商模型层；提供商、模型与选中项存储在本插件存储区。"

    override val inject: Dependencies =
        dependencies(ServiceDirectory.Key, PluginStorage.Key, PermissionService.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val permissions = ctx.require(PermissionService.Key)
        val repository = ProviderStore(ctx.require(PluginStorage.Key))
        val network = NetworkGate { permissions.require(HostPermission.NETWORK) }
        val transport = OkHttpProviderTransport()
        val modelService = AgentModelService(repository, network, transport)
        val providerService = ProviderServiceImpl(repository, network, transport)
        directory.publish(ModelService.Key, modelService)
        directory.publish(ModelProviderService.Key, providerService)
        // Disabling the plugin retracts both capabilities in the same step that disposes them, so a
        // consumer never holds a service whose storage and permission provider are already gone.
        effect.collect {
            if (directory.lookup(ModelService.Key) === modelService) directory.retract(ModelService.Key)
            if (directory.lookup(ModelProviderService.Key) === providerService) {
                directory.retract(ModelProviderService.Key)
            }
        }
    }
}
