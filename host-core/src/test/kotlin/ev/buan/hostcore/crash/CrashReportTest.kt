package ev.buan.hostcore.crash

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/** The report is read by a human with adb, so its facts must be findable as plain text. */
class CrashReportTest {

    private val instant: Instant = Instant.parse("2026-10-24T10:00:00Z")

    @Test
    fun `报告包含时间线程疑似插件与异常栈`() {
        val report = CrashReport.format(instant, "main", "plugin.a", IllegalStateException("boom"))

        assertTrue(report.contains("时间：2026-10-24T10:00:00Z"), report)
        assertTrue(report.contains("线程：main"), report)
        assertTrue(report.contains("疑似插件：plugin.a"), report)
        assertTrue(report.contains("IllegalStateException"), report)
        assertTrue(report.contains("boom"), report)
        // The no-error-boundary caveat belongs in the artifact, not only in code comments.
        assertTrue(report.contains("Compose 没有错误边界"), report)
        assertTrue(report.contains("安全模式"), report)
    }

    @Test
    fun `未知归因明确写成未知`() {
        val report = CrashReport.format(instant, "main", null, RuntimeException("x"))

        assertTrue(report.contains("疑似插件：未知"), report)
    }

    @Test
    fun `嵌套异常保留 Caused by`() {
        val error = RuntimeException("outer", IllegalArgumentException("inner"))

        val report = CrashReport.format(instant, "main", null, error)

        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: inner"), report)
    }

    @Test
    fun `报告是多行文本且报告与异常栈之间有空行`() {
        val report = CrashReport.format(instant, "main", "plugin.a", IllegalStateException("boom"))

        assertTrue(report.startsWith("EV 崩溃报告\n"), report)
        assertTrue(report.contains("\n\n"), "报告与异常栈之间应留空行: $report")
    }
}
