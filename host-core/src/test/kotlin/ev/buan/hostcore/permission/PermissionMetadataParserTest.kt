package ev.buan.hostcore.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parser is the only place a metadata string becomes a permission declaration, so these tests
 * pin the two shapes (APK comma string / jar sidecar array), the default-deny empty case, and the
 * accept/reject split between known and unknown names.
 */
class PermissionMetadataParserTest {

    // The host passes `HostPermission.wireNames`; this module deliberately does not know them, so
    // the test supplies the same six names the plugin contract defines.
    private val supported = setOf(
        "NETWORK",
        "PLUGIN_STORAGE",
        "BACKGROUND",
        "NOTIFICATIONS",
        "CLIPBOARD",
        "INSTALL_PACKAGES",
    )

    // ---- APK: comma separated string ---------------------------------------

    @Test
    fun `APK 逗号字符串解析为声明集合`() {
        val declaration = PermissionMetadataParser.parseApkMetadata(
            " NETWORK , PLUGIN_STORAGE , NETWORK ",
            supported,
        )
        assertEquals(setOf("NETWORK", "PLUGIN_STORAGE"), declaration.declared)
        assertEquals(emptySet(), declaration.unknown)
        assertTrue(declaration.isWellFormed)
    }

    @Test
    fun `APK 缺少元数据表示未声明任何权限`() {
        for (raw in listOf(null, "", "  ", " , ,, ")) {
            val declaration = PermissionMetadataParser.parseApkMetadata(raw, supported)
            assertEquals(emptySet(), declaration.declared, "raw=$raw")
            assertEquals(emptySet(), declaration.unknown, "raw=$raw")
            assertTrue(declaration.isWellFormed, "raw=$raw")
        }
    }

    @Test
    fun `APK 未知权限被拒绝`() {
        val declaration = PermissionMetadataParser.parseApkMetadata(
            "NETWORK,CAMERA",
            supported,
        )
        assertEquals(setOf("NETWORK"), declaration.declared)
        assertEquals(setOf("CAMERA"), declaration.unknown)
        assertFalse(declaration.isWellFormed)
    }

    // ---- jar/dex: sidecar array --------------------------------------------

    @Test
    fun `sidecar 数组解析为声明集合`() {
        val declaration = PermissionMetadataParser.parseJarMetadata(
            listOf("BACKGROUND", " NOTIFICATIONS ", "BACKGROUND", ""),
            supported,
        )
        assertEquals(setOf("BACKGROUND", "NOTIFICATIONS"), declaration.declared)
        assertEquals(emptySet(), declaration.unknown)
        assertTrue(declaration.isWellFormed)
    }

    @Test
    fun `sidecar 缺少字段表示未声明任何权限`() {
        val declaration = PermissionMetadataParser.parseJarMetadata(null, supported)
        assertEquals(emptySet(), declaration.declared)
        assertEquals(emptySet(), declaration.unknown)
        assertTrue(declaration.isWellFormed)
    }

    @Test
    fun `sidecar 未知权限被拒绝而已知权限保留`() {
        val declaration = PermissionMetadataParser.parseJarMetadata(
            listOf("PLUGIN_STORAGE", "TELEPORT"),
            supported,
        )
        assertEquals(setOf("PLUGIN_STORAGE"), declaration.declared)
        assertEquals(setOf("TELEPORT"), declaration.unknown)
        assertFalse(declaration.isWellFormed)
    }

    // ---- vocabulary --------------------------------------------------------

    @Test
    fun `已知权限放行且未知权限拒绝`() {
        val accepted = PermissionMetadataParser.parseJarMetadata(listOf("NOTIFICATIONS"), supported)
        assertTrue(accepted.isWellFormed)
        assertEquals(setOf("NOTIFICATIONS"), accepted.declared)

        val rejected = PermissionMetadataParser.parseJarMetadata(
            listOf("NOTIFICATIONS", "ROOT"),
            supported,
        )
        assertFalse(rejected.isWellFormed)
        assertEquals(setOf("NOTIFICATIONS"), rejected.declared)
        assertEquals(setOf("ROOT"), rejected.unknown)
    }

    @Test
    fun `权限名大小写敏感且不做模糊匹配`() {
        val declaration = PermissionMetadataParser.parseApkMetadata("network,Network", supported)
        assertEquals(emptySet(), declaration.declared)
        assertEquals(setOf("network", "Network"), declaration.unknown)
        assertFalse(declaration.isWellFormed)
    }

    @Test
    fun `声明顺序按元数据保留且去重`() {
        val declaration = PermissionMetadataParser.parseApkMetadata(
            "CLIPBOARD, NETWORK, CLIPBOARD",
            supported,
        )
        assertEquals(listOf("CLIPBOARD", "NETWORK"), declaration.declared.toList())
    }
}
