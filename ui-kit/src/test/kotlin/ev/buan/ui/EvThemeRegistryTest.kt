package ev.buan.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvThemeRegistryTest {

    @Test
    fun builtInIdsAreUniqueAndDefaultIsListed() {
        val ids = EvThemeRegistry.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "theme ids must be unique")
        assertTrue(DEFAULT_THEME_ID in ids, "the default theme must be selectable")
    }

    @Test
    fun unknownIdResolvesToDefault() {
        assertEquals(EvThemeSpec.Default.id, EvThemeRegistry.resolve("no-such-theme").id)
    }

    @Test
    fun builtInIdCannotBeShadowed() {
        val impostor = EvThemeSpec(id = DEFAULT_THEME_ID, name = "假冒")
        assertFalse(EvThemeRegistry.register(impostor))
        assertEquals("默认", EvThemeRegistry.resolve(DEFAULT_THEME_ID).name)
    }

    @Test
    fun externalThemeCanBeRegisteredReplacedAndRemoved() {
        val id = "test-external-theme"
        try {
            assertTrue(EvThemeRegistry.register(EvThemeSpec(id = id, name = "甲")))
            assertEquals("甲", EvThemeRegistry.resolve(id).name)

            // Re-registering the same id replaces the previous definition instead of duplicating it.
            assertTrue(EvThemeRegistry.register(EvThemeSpec(id = id, name = "乙")))
            assertEquals(1, EvThemeRegistry.all().count { it.id == id })
            assertEquals("乙", EvThemeRegistry.resolve(id).name)
        } finally {
            EvThemeRegistry.unregister(id)
        }
        assertEquals(EvThemeSpec.Default.id, EvThemeRegistry.resolve(id).id)
    }
}
