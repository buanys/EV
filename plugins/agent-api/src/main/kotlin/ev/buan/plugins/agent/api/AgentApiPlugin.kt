package ev.buan.plugins.agent.api

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/**
 * Empty shell: exists only so the container holding the agent contracts can be installed, and so
 * plugins that declare a dependency on it get these types through the dependency loader.
 *
 * It publishes no service and owns no state - the contracts themselves are the payload.
 */
class AgentApiPlugin : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit
}
