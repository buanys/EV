package ev.buan.plugins.agent.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.MemoryStoreService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentPage
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
 *
 * It also owns its own screen. The memory page used to live inside ev-ui, which had to import the
 * session model and the storage layout to draw it - a UI plugin depending on another plugin's data
 * shape. Now the page ships with the data: whoever is opened is whoever knows how to show it.
 */
class AgentMemoryPlugin :
    Plugin<Unit>,
    AgentSurface,
    AgentPage,
    org.cordis.api.ManifestProvider {

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.memory",
        name = "记忆",
        version = "1.1.0",
        description = "按会话保存事实，下次对话时取回；不是把整段聊天塞进上下文。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("agent/memory", org.cordis.api.PluginCapability.Kind.SERVICE, "读写记忆", "按会话存一条事实，按相关度取回。"),
            org.cordis.api.PluginCapability("agent/memory-store", org.cordis.api.PluginCapability.Kind.SERVICE, "跨会话记事本", "模型自己增删改的长期条目，以及常驻在提示词里的那部分。"),
        ),
    )

    override val name = "agent-memory"
    override val title = "agent 记忆"
    override val body = "会话记忆按 JSON 存在宿主的用户数据区，发布为 MemoryService。"

    override val inject: Dependencies =
        dependencies(ServiceDirectory.Key, PluginStorage.Key, UserDataStorage.Key)

    /** Set during apply; the page is only reachable after activation, so it is always populated. */
    private lateinit var memory: StorageMemoryService

    /** The notebook behind [MemoryStoreService]; the page shows it beside the transcripts. */
    private lateinit var notebook: NoteStore

    override val pageTitle: String = "记忆"

    /**
     * Opened from a menu, never the app root: the launch screen belongs to the conversation, and a
     * fallback that landed on the memory list would look like data loss to whoever sees it.
     */
    override val isHomeCandidate: Boolean = false

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) {
        MemoryPage(memory = memory, notes = notebook, modifier = modifier, onBack = onBack)
    }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // Memory is the user's data, not the plugin's: it survives disabling, reinstalling and
        // re-approval. The plugin area is read only once, to migrate what was stored there before.
        val storage = ctx.require(UserDataStorage.Key)
        migrateToUserData(ctx.get(PluginStorage.Key), storage)

        // Distillation gets its own scope: a slow or failing model call must never sit inside a
        // turn, and disabling the plugin cancels whatever batch is in flight with it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var distiller: Distiller? = null

        val service = StorageMemoryService(
            storage = storage,
            onAppend = { sessionId -> distiller?.onMessageStored(sessionId) },
        )
        memory = service
        directory.publish(MemoryService.Key, service)

        // L2/L3: the notebook the model edits and the block rendered into its prompt.
        val notes = NoteStore(storage)
        notebook = notes
        directory.publish(MemoryStoreService.Key, notes)

        distiller = Distiller(
            storage = storage,
            memory = service,
            notes = notes,
            // Looked up per batch, not captured at activation: the model plugin can be enabled,
            // reconfigured or replaced while memory stays running.
            model = { directory.lookup(ModelService.Key) },
            scope = scope,
        )

        // Tools go through the published registry rather than a second one: the host holds
        // exactly one registry, and a capability plugin registers into it. A missing registry
        // (agent-tools disabled) only costs the tools, not the service.
        val registry = directory.lookup(ToolRegistry.Key)
        registry?.register(PLUGIN_ID, MemoryTools.all(notes))

        // Disabling the plugin retracts the capability in the same step that disposes it, so a
        // consumer never keeps a memory service whose storage access is already gone. The identity
        // checks keep this stale disposer from retracting a newer instance after a re-activation.
        effect.collect {
            scope.cancel()
            registry?.unregister(PLUGIN_ID)
            if (directory.lookup(MemoryStoreService.Key) === notes) {
                directory.retract(MemoryStoreService.Key)
            }
            if (directory.lookup(MemoryService.Key) === service) directory.retract(MemoryService.Key)
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.memory"
    }
}
