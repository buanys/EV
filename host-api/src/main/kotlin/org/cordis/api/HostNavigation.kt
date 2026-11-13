package org.cordis.api

import org.cordis.ServiceKey

/**
 * Lets a plugin page ask the host to open host-owned screens, without knowing Activity class names
 * or how the host routes navigation.
 */
interface HostNavigation {
    fun openPluginManager()

    fun openSettings()

    companion object {
        val Key = ServiceKey<HostNavigation>("host/navigation")
    }
}
