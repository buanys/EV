package org.cordis.api

import org.cordis.ServiceKey

/**
 * Host-owned directory for plugin-to-plugin capabilities.
 *
 * Cordis service injection is deliberately strict: a plugin that declares a dependency waits for
 * it, and a plugin that does not declare it may not look it up at all. Both are wrong for an
 * optional backend - the consumer would either hang until the host's start timeout, or crash.
 *
 * The directory is always provided by the host, so consumers always activate; a missing publisher
 * is a null lookup they can render as "not ready".
 */
interface ServiceDirectory {
    fun <T : Any> publish(key: ServiceKey<T>, value: T)

    fun <T : Any> lookup(key: ServiceKey<T>): T?

    fun retract(key: ServiceKey<*>)

    companion object {
        val Key = ServiceKey<ServiceDirectory>("host/service-directory")
    }
}
