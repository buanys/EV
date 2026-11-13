package ev.buan.hostcore

/**
 * Marker for the host-only core module.
 *
 * `:host-core` holds host-side logic that must stay out of the two published SDK modules:
 * `:plugin-api` is the plugin contract and `:ui-kit` is the design system, so anything only the
 * Android host needs (validation, per-plugin storage, crash bookkeeping, dependency resolution)
 * belongs here instead. The module is a plain Kotlin/JVM library on purpose: it has no Android
 * dependency, so its logic is unit-testable on the JVM without Robolectric or a device.
 *
 * The real host-core content (`PluginValidator`, `DexClassScanner`, `PermissionStore`,
 * `PluginStorage`, `DependencyResolver`, ...) is added by the follow-up tasks that consume it.
 */
object HostCore {
    /** Stable module identifier, useful in logs and in diagnostics screens. */
    const val MODULE_NAME: String = "host-core"
}
