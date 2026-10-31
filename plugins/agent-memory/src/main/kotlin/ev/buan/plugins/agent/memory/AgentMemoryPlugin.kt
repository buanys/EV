package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.api.UserDataStorage
import org.cordis.api.migrateToUserData
import org.cordis.dependencies

/**
 * Publishes the JSON-backed [MemoryService] into the host's [ServiceDirectory].
 *
 * A Cordis service would make every consumer a hard dependency and leave it waiting while this
 * plugin is disabled; the directory is a lookup, so an agent degrades to stateless instead.
 */
class AgentMemoryPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-memory"
    override val title = "agent 记忆"
    override val body = "会话记忆按 JSON 存在宿主的用户数据区，发布为 MemoryService。"

    override val inject: Dependencies =
        dependencies(ServiceDirectory.Key, PluginStorage.Key, UserDataStorage.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // Memory is the user's data, not the plugin's: it survives disabling, reinstalling and
        // re-approval. The plugin area is read only once, to migrate what was stored there before.
        val storage = ctx.require(UserDataStorage.Key)
        migrateToUserData(ctx.get(PluginStorage.Key), storage)
        val memory = StorageMemoryService(storage)
        directory.publish(MemoryService.Key, memory)
        // Disabling the plugin retracts the capability in the same step that disposes it, so a
        // consumer never keeps a memory service whose storage access is already gone. The identity
        // check keeps this stale disposer from retracting a newer instance after a re-activation.
        effect.collect {
            if (directory.lookup(MemoryService.Key) === memory) directory.retract(MemoryService.Key)
        }
    }
}
