package ev.buan.hostcore.validation

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexClassScannerTest {

    private val reserved = listOf("java", "android", "kotlin", "org.json", "org.cordis")

    @Test
    fun `isReserved 命中整包与子包`() {
        assertTrue(DexClassScanner.isReserved("org.json", reserved))
        assertTrue(DexClassScanner.isReserved("org.json.JSONObject", reserved))
        assertTrue(DexClassScanner.isReserved("kotlin.Unit", reserved))
    }

    @Test
    fun `isReserved 不误伤前缀相近的包`() {
        assertFalse(DexClassScanner.isReserved("org.jsonish.Foo", reserved))
        assertFalse(DexClassScanner.isReserved("ev.buan.plugins.settings.SettingsPlugin", reserved))
    }

    @Test
    fun `非 dex 内容不产生类定义`() {
        assertEquals(emptyList(), DexClassScanner.definedClasses("not a dex".toByteArray()))
    }

    @Test
    fun `容器里没有 dex 时不报违规`() {
        assertEquals(emptyList(), DexClassScanner.violations(zipOf("readme.txt" to byteArrayOf(1)), reserved))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): java.io.File {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return kotlin.io.path.createTempFile("ev-test", ".jar").toFile().apply { writeBytes(bytes.toByteArray()) }
    }
}
