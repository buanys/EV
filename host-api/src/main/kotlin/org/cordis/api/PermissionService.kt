package org.cordis.api

import org.cordis.ServiceKey

/**
 * This plugin's host permissions, as seen from inside the plugin.
 *
 * The host provides one instance per plugin, on that plugin's own child Context, so a plugin only
 * ever observes its own grants. [isGranted] reads the live grant state: revoking a permission takes
 * effect for the next call, not just for the next activation. [require] turns a missing grant into a
 * [SecurityException] so every host-mediated service fails closed instead of degrading silently.
 *
 * Per ADR-0003 this is an API-level gate, not a sandbox: a plugin shares the host process and can
 * still call Android directly. What it cannot do is use a host service without declaring the
 * permission in its metadata and being granted it by the user.
 */
interface PermissionService {
    /** Whether the user currently grants [permission] to this plugin. */
    fun isGranted(permission: HostPermission): Boolean

    /**
     * Confirms [permission] is granted, otherwise throws [SecurityException].
     *
     * The message carries the `PERMISSION_DENIED` code so logs and the management page agree on one
     * failure identity. Host-mediated APIs call this before lending capability, which is why it
     * throws rather than returning false.
     */
    fun require(permission: HostPermission)

    companion object {
        val Key = ServiceKey<PermissionService>("host/permission")
    }
}
