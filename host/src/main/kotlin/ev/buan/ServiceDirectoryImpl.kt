package ev.buan

import org.cordis.ServiceKey
import org.cordis.api.ServiceDirectory
import java.util.concurrent.ConcurrentHashMap

internal class ServiceDirectoryImpl : ServiceDirectory {

    private val entries = ConcurrentHashMap<String, Any>()

    override fun <T : Any> publish(key: ServiceKey<T>, value: T) {
        entries[key.name] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> lookup(key: ServiceKey<T>): T? = entries[key.name] as? T

    override fun retract(key: ServiceKey<*>) {
        entries.remove(key.name)
    }
}

/**
 * Owns plugin lifetime for one process.
 *
 * Sync is restart-scoped by design, but enabling or disabling a single plugin is live: it disposes
 * the Fiber or loads the artifact again without restarting the app.
 */
