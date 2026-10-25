package ev.buan.plugins.ping.api

import org.cordis.ServiceKey

/**
 * The contract itself lives in a plugin, not in the host: this is the experiment. ping-impl and
 * ping-ui only get this class through the module dependency loader.
 */
interface PingService {
    fun ping(): String

    companion object {
        val Key = ServiceKey<PingService>("demo/ping")
    }
}
