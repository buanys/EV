package org.cordis.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory storage for both areas: the migration helpers are pure file operations, so they can be tested
 * without a device, a host or a real file system.
 */
private class FakeStorage(
    private val files: MutableMap<String, ByteArray> = linkedMapOf(),
    /** Simulates a revoked grant: every call fails the way the real gated storage does. */
    private val denied: Boolean = false,
) : UserDataStorage {
    override fun read(path: String): ByteArray? {
        if (denied) throw SecurityException("PERMISSION_DENIED")
        return files[path]
    }

    override fun write(path: String, bytes: ByteArray) {
        if (denied) throw SecurityException("PERMISSION_DENIED")
        files[path] = bytes
    }

    override fun atomicWrite(path: String, bytes: ByteArray) = write(path, bytes)

    override fun delete(path: String): Boolean {
        if (denied) throw SecurityException("PERMISSION_DENIED")
        return files.remove(path) != null
    }

    override fun list(path: String): List<String> {
        if (denied) throw SecurityException("PERMISSION_DENIED")
        val prefix = if (path.isEmpty()) "" else "$path/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()
    }
}

class UserDataMigrationTest {

    @Test
    fun copiesLegacyFilesIntoUserData() {
        val legacy = FakeStorage(mutableMapOf("sessions.json" to "old".toByteArray()))
        val userData = FakeStorage()

        migrateToUserData(legacy, userData)

        assertEquals("old", userData.read("sessions.json")?.decodeToString())
        assertTrue(userData.read(".migrated-from-plugin-area") != null, "marker written after a copy")
    }

    @Test
    fun newerUserDataIsNeverOverwritten() {
        val legacy = FakeStorage(mutableMapOf("sessions.json" to "old".toByteArray()))
        val userData = FakeStorage(mutableMapOf("sessions.json" to "new".toByteArray()))

        migrateToUserData(legacy, userData)

        assertEquals("new", userData.read("sessions.json")?.decodeToString())
    }

    @Test
    fun revokedGrantDoesNotThrow() {
        val legacy = FakeStorage(mutableMapOf("sessions.json" to "old".toByteArray()))
        val userData = FakeStorage(denied = true)

        // Plugin activation must survive this: the migration just retries on a later start.
        migrateToUserData(legacy, userData)
    }

    @Test
    fun legacyIsNotReadAgainOnceTheMarkerExists() {
        val legacy = FakeStorage(mutableMapOf("sessions.json" to "old".toByteArray()))
        val userData = FakeStorage(mutableMapOf(".migrated-from-plugin-area" to "1".toByteArray()))

        migrateToUserData(legacy, userData)

        // A file the user deleted after the migration must stay deleted.
        assertNull(userData.read("sessions.json"))
    }

    @Test
    fun missingLegacyBindingIsANoOp() {
        val userData = FakeStorage()

        migrateToUserData(null, userData)

        assertNull(userData.read(".migrated-from-plugin-area"))
    }
}
