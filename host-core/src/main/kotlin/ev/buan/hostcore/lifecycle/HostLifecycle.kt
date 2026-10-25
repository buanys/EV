package ev.buan.hostcore.lifecycle

/**
 * Process-level answer to "may this runtime hand a plugin page to a screen".
 *
 * A restarted process owns an Application and no loaded plugins, and a safe-mode launch loads none
 * by design. Rendering a plugin's full page in either state produces an empty shell, so the host
 * only advertises itself as started after a complete sync + activation pass that did not run in safe
 * mode. `PluginPageActivity` reads that fact to choose between rendering the page and going back to
 * the host screen; the decision lives here - not in the Android-only runtime - so it is unit-tested
 * on the JVM without a device.
 *
 * This is a flag, not a state machine. A later start that fails does not revoke an earlier
 * successful one: the plugins that passed are still loaded and their pages still render. Only
 * [stop] (dispose) or a start that actually ran in safe mode clears it.
 *
 * Threading: [completeStart] and [stop] are called from the start-up coroutine (IO dispatcher) while
 * readers sit on UI threads, hence the volatile field.
 */
class HostLifecycle {

    @Volatile
    private var started: Boolean = false

    /** True once a start pass finished outside safe mode and the runtime has not been stopped. */
    val hasStarted: Boolean get() = started

    /**
     * Records the end of one start pass, after sync and activation finished.
     *
     * [safeMode] must be the mode the pass actually ran in: safe mode skipped every plugin, so it
     * must not count as started. The failure path never calls this, which is what keeps a start that
     * threw from advertising itself.
     */
    fun completeStart(safeMode: Boolean) {
        started = !safeMode
    }

    /**
     * Records that the runtime stopped and unloaded every plugin.
     *
     * Nothing is left to render, so a plugin page restored afterwards has to go back to the host
     * screen instead of composing a dead shell.
     */
    fun stop() {
        started = false
    }
}
