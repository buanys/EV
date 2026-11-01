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
import org.cordis.api.UserDataStorage
import org.cordis.api.migrateToUserData
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
 * Configuration (keys included) lives in the user's [UserDataStorage] (`config/providers.json`). The
 * old copy in this plugin's [PluginStorage] area is migrated once. Capabilities used: `NETWORK` for
 * HTTP calls and `PLUGIN_STORAGE` for the document - both declared in the container metadata, so
 * activation is gated until the user grants them (ADR-0003).
 */
class AgentModelPlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.model",
        name = "模型服务",
        version = "0.1.0",
        description = "OpenAI 兼容的模型调用：provider、Key、模型清单都在这里。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("agent/model", org.cordis.api.PluginCapability.Kind.SERVICE, "提供模型调用", "complete() 跑一次补全，供 agent-brain 使用。"),
            org.cordis.api.PluginCapability("agent/model-providers", org.cordis.api.PluginCapability.Kind.SERVICE, "管理 provider", "地址、Key、模型列表的读写与连通性测试。"),
        ),
        permissions = listOf("android.permission.INTERNET"),
        settings = listOf(
            org.cordis.api.PluginSetting("provider", "当前 provider", org.cordis.api.PluginSetting.Kind.TEXT, "模型请求发往哪里。"),
        ),
    )

    override val name = "agent-model"
    override val title = "模型服务"
    override val body = "OpenAI 兼容的多提供商模型层；提供商、模型与选中项存储在宿主的用户数据区，不随插件卸载或重装丢失。"

    override val inject: Dependencies =
        dependencies(ServiceDirectory.Key, PluginStorage.Key, UserDataStorage.Key, PermissionService.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val permissions = ctx.require(PermissionService.Key)
        // Keys and provider settings are the user's data: they live in the host-owned user-data area,
        // so disabling, reinstalling or re-approving this plugin never makes the user type them again.
        val userData = ctx.require(UserDataStorage.Key)
        migrateToUserData(ctx.get(PluginStorage.Key), userData)
        val repository = ProviderStore(userData)
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
