package ev.buan.hostcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wiring smoke test for the pure-JVM `:host-core` module: it proves the module compiles, that
 * JUnit 5 is on the test runtime classpath and that the public package is the host-only namespace
 * (`ev.buan.hostcore`) rather than the plugin contract's `org.cordis` / `ev.buan.api`.
 *
 * Behaviour that matters is tested next to the code that introduces it (permission metadata parsing,
 * storage path handling, dependency resolution).
 */
class HostCoreTest {

    @Test
    fun moduleNameIsStable() {
        assertEquals("host-core", HostCore.MODULE_NAME)
    }

    @Test
    fun hostCoreLivesInItsOwnNamespace() {
        assertTrue(
            HostCore::class.java.name.startsWith("ev.buan.hostcore."),
            "host-core 必须留在 ev.buan.hostcore 命名空间，实际为 ${HostCore::class.java.name}",
        )
    }
}
