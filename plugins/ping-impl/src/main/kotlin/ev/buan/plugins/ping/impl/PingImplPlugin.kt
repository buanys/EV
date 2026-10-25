package ev.buan.plugins.ping.impl

import ev.buan.plugins.ping.api.PingService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * Publishes the implementation into the host directory instead of registering a Cordis service.
 * A Cordis service would make every consumer a hard dependency: disabling this plugin would leave
 * them waiting until the host times out.
 */
class PingImplPlugin : Plugin<Unit> {
    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        directory.publish(PingService.Key, object : PingService {
            override fun ping(): String = "pong from ping-impl"
        })
    }
}
