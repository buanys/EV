package ev.buan.plugins.ping.api

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Empty shell: exists only so the container holding [PingService] can be installed. */
class PingApiPlugin : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit
}
