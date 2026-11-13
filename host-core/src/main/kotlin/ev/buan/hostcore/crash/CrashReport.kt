package ev.buan.hostcore.crash

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Formats the text stored as `files/crash/last.txt`.
 *
 * Plain text on purpose: it is written from a crashing process, and the only readers are the next
 * launch (which does not parse it) and a human pulling it with `adb`. The suspected plugin is
 * labelled as a hint because the host cannot attribute a crash to one plugin - everything runs in
 * the same process, and Compose has no error boundary to contain a plugin UI exception.
 */
object CrashReport {

    fun format(
        recordedAt: Instant,
        threadName: String,
        suspectedPluginId: String?,
        error: Throwable,
    ): String {
        val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }
        return buildString {
            appendLine("EV 崩溃报告")
            appendLine("时间：$recordedAt")
            appendLine("线程：$threadName")
            appendLine("疑似插件：${suspectedPluginId ?: "未知"}（仅提示，宿主不做进程内隔离）")
            appendLine("说明：Compose 没有错误边界，本次崩溃无法在进程内恢复；下一次启动将进入安全模式。")
            appendLine()
            append(trace)
        }
    }
}
