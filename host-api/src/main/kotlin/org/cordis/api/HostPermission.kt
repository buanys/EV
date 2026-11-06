package org.cordis.api

/**
 * A host capability a container may ask for.
 *
 * Per ADR-0003 (D3 = A) this is a *declaration + user grant + API-level gate*, not an OS sandbox:
 * a plugin runs in the host process under the host UID, so this enum cannot stop direct Android
 * calls. What it can gate is a host-mediated service the host is willing to lend.
 *
 * Enforcement is not uniform; the constant KDoc below is the source of truth:
 * - [PLUGIN_STORAGE] and [BACKGROUND] are checked by the host before it lends the corresponding
 *   service (activation gate / service call), so they are real gates;
 * - [NETWORK] has no host network service at all: it records intent and shows up on the user's
 *   grant surface, but it cannot stop a plugin from calling the host-shared OkHttp directly;
 * - [NOTIFICATIONS], [CLIPBOARD] and [INSTALL_PACKAGES] are reserved vocabulary: the host has no
 *   mediated service for them yet, so they are declaration/grant only.
 *
 * Metadata spelling is the constant name (`cordis.permissions` is a comma-separated string for
 * APKs, `"permissions": [...]` in the jar sidecar). Adding a constant is a contract change: old
 * hosts reject containers that declare a permission they do not know, by design.
 */
enum class HostPermission {
    /**
     * Declaration and audit only - the host has **no network service** to lend.
     *
     * The app's `INTERNET` permission comes transitively from the host's own okhttp dependency, and
     * plugins share the host process/UID, so a plugin can call the host-shared OkHttp directly.
     * Revoking this grant therefore does not remove connectivity; it only records intent on the
     * user-visible grant surface.
     */
    NETWORK,

    /**
     * Enforced: `PluginStorage` is not usable until the user grants this.
     *
     * The host checks the grant at activation and only then builds the plugin's child Context with
     * the storage service, so an ungranted plugin has no `PluginStorage` to call.
     */
    PLUGIN_STORAGE,

    /**
     * Enforced: `BackgroundService.acquire` requires this grant and throws `SecurityException`
     * without it.
     */
    BACKGROUND,

    /**
     * Reserved: declaration and grant only. The host has no mediated notification service yet, so
     * this grant does not gate any API.
     */
    NOTIFICATIONS,

    /** Reserved: declaration and grant only; no host-mediated clipboard service yet. */
    CLIPBOARD,

    /** Reserved: declaration and grant only; no host-mediated package-install service yet. */
    INSTALL_PACKAGES,

    /**
     * Declaration, grant and audit only - **the host cannot gate `su`**.
     *
     * A plugin runs in the host process, so once it decides to call `Runtime.exec("su")` there is
     * no host-mediated service to revoke; the real gates are outside this enum: Magisk (or the
     * ROM's su provider) asks the user per-UID, and a well-behaved plugin routes every dangerous
     * call through the agent loop's approval gate. This constant exists so the declaration shows
     * up on the user's grant surface and the install/audit trail records that a container intends
     * to touch root - root must never be a default capability that hides inside a generic plugin.
     */
    ROOT_SHELL,
    ;

    companion object {
        /** The wire spelling used in plugin metadata. Deliberately the constant name. */
        val wireNames: Set<String> = entries.mapTo(LinkedHashSet()) { it.name }

        /** Metadata string to permission, or null when this host does not know the name. */
        fun fromWireName(name: String): HostPermission? = entries.firstOrNull { it.name == name }

        /**
         * [requested] minus [granted], in declaration order.
         *
         * The one rule the install pass (calls it per candidate), the activation gate and the host
         * service implementations share: a permission is usable only when both sides contain it.
         */
        fun missing(
            requested: Set<HostPermission>,
            granted: Set<HostPermission>,
        ): Set<HostPermission> = requested.filterTo(LinkedHashSet()) { it !in granted }
    }
}
