package ev.buan.hostcore.crash

import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The crash marker pair: write ordering, fail-safe reads (a present marker always means safe mode)
 * and the hint fields. Plain temp directories, no Android.
 */
class CrashMarkerTest {

    @TempDir
    lateinit var temp: Path

    private val filesDir: File
        get() = temp.resolve("files").toFile()

    private val instant: Instant = Instant.parse("2026-10-24T10:00:00Z")

    @Test
    fun `没有标记时下一次启动是正常运行`() {
        assertNull(CrashMarker.pending(filesDir))
        assertFalse(CrashMarker.reportFile(filesDir).exists())
        assertFalse(CrashMarker.pendingFile(filesDir).exists())
    }

    @Test
    fun `record 写报告与标记并保留时间与疑似插件`() {
        val recorded = CrashMarker.record(filesDir, "报告内容\n", "ev.buan.plugins.example", instant)

        assertTrue(recorded)
        assertTrue(CrashMarker.reportFile(filesDir).isFile)
        assertTrue(CrashMarker.pendingFile(filesDir).isFile)
        assertEquals("报告内容\n", CrashMarker.reportFile(filesDir).readText())

        val pending = assertNotNull(CrashMarker.pending(filesDir))
        assertEquals("报告内容\n", pending.report)
        assertEquals("ev.buan.plugins.example", pending.suspectedPluginId)
        assertEquals(instant, pending.recordedAt)
    }

    @Test
    fun `record 会创建缺失的 crash 目录`() {
        val nested = temp.resolve("does/not/exist/files").toFile()

        assertTrue(CrashMarker.record(nested, "报告", null, instant))
        assertEquals(instant, assertNotNull(CrashMarker.pending(nested)).recordedAt)
    }

    @Test
    fun `标记存在但报告丢失时仍然进入安全模式`() {
        CrashMarker.record(filesDir, "报告", "plugin.a", instant)
        assertTrue(CrashMarker.reportFile(filesDir).delete())

        val pending = assertNotNull(CrashMarker.pending(filesDir))
        assertEquals("", pending.report)
        assertEquals("plugin.a", pending.suspectedPluginId)
        assertEquals(instant, pending.recordedAt)
    }

    @Test
    fun `标记内容损坏时仍然进入安全模式并降级不可解析字段`() {
        CrashMarker.directory(filesDir).mkdirs()
        CrashMarker.pendingFile(filesDir).writeText("不是键值对\nbroken=\ntime=not-a-time\n")

        val pending = assertNotNull(CrashMarker.pending(filesDir))
        assertEquals("", pending.report)
        assertNull(pending.suspectedPluginId)
        assertNull(pending.recordedAt)
    }

    @Test
    fun `clearPending 只删标记并保留报告`() {
        CrashMarker.record(filesDir, "报告", "plugin.a", instant)

        assertTrue(CrashMarker.clearPending(filesDir))
        assertNull(CrashMarker.pending(filesDir))
        assertTrue(CrashMarker.reportFile(filesDir).isFile)
        // Idempotent: leaving safe mode twice, or with no marker at all, is not an error.
        assertTrue(CrashMarker.clearPending(filesDir))
    }

    @Test
    fun `新崩溃覆盖上一次的报告与标记`() {
        CrashMarker.record(filesDir, "第一次", "plugin.a", Instant.parse("2026-10-24T10:00:00Z"))
        val second = Instant.parse("2026-10-24T11:00:00Z")
        CrashMarker.record(filesDir, "第二次", "plugin.b", second)

        val pending = assertNotNull(CrashMarker.pending(filesDir))
        assertEquals("第二次", pending.report)
        assertEquals("plugin.b", pending.suspectedPluginId)
        assertEquals(second, pending.recordedAt)
    }

    @Test
    fun `插件 id 中的换行与等号不会伪造标记字段`() {
        val hostile = "evil\nplugin=forged\n=value"
        CrashMarker.record(filesDir, "报告", hostile, instant)

        val pending = assertNotNull(CrashMarker.pending(filesDir))
        assertEquals(hostile, pending.suspectedPluginId)
        assertEquals(instant, pending.recordedAt)
    }
}
