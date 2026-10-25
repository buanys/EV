package ev.buan.plugins.agent.web

import java.security.SecureRandom
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * Serves the agent's web console on the LAN: the React build from this plugin's `web/` storage
 * directory plus a JSON API bridging to whatever [ev.buan.plugins.agent.api.AgentService] is
 * published. The brain is looked up per request, so this plugin activates and serves a readable
 * "agent 未连接" even when the whole agent stack is disabled.
 *
 * The access token is generated once and kept in this plugin's storage; the surface card shows it
 * so the owner can type it into the browser. Root is not involved — this plugin asks only for
 * NETWORK and its own storage.
 */
class AgentWebPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-web"
    override val title = "agent Web 控制台"
    override val body = "局域网 Web 门面：聊天 + 工具审批（v1）。端口 ${WebConsoleServer.DEFAULT_PORT}，令牌见存储区 web/token.txt。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, PluginStorage.Key)

    @Volatile
    private var server: WebConsoleServer? = null

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val storage = ctx.require(PluginStorage.Key)
        val token = tokenOf(storage)
        val started = WebConsoleServer(
            port = WebConsoleServer.DEFAULT_PORT,
            token = token,
            storage = storage,
            directory = directory,
        )
        started.start()
        server = started
        // Disabling the plugin closes the port in the same step that disposes it.
        effect.collect {
            server = null
            started.stop()
        }
    }

    /** First boot mints a token; later boots reuse it so bookmarks keep working. */
    private fun tokenOf(storage: PluginStorage): String {
        storage.read(TOKEN_FILE)?.decodeToString()?.trim()?.takeIf { it.length >= 16 }?.let { return it }
        val random = SecureRandom()
        val token = (1..TOKEN_LENGTH)
            .map { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] }
            .joinToString("")
        storage.atomicWrite(TOKEN_FILE, token.encodeToByteArray())
        return token
    }

    private companion object {
        const val TOKEN_FILE = "web/token.txt"
        const val TOKEN_LENGTH = 24
        const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    }
}
