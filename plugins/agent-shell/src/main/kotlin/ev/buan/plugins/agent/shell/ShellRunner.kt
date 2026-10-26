package ev.buan.plugins.agent.shell

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * What one shell execution produced, before any tool-level encoding.
 *
 * [exitCode] is null when [timedOut]: a killed process has no meaningful status. Output fields are
 * already capped at [MAX_CAPTURE_BYTES] each; the truncated flags tell the encoder to say so
 * instead of letting a cut-off line masquerade as complete output.
 */
internal data class RawShellResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    /**
     * 实际用的是哪个 shell：`su`（root）或 `/system/bin/sh`（应用自己的 uid）。
     *
     * 必须回报给 agent：同一条 `ls /data/data/...`，root 下成功、普通 uid 下 Permission denied，
     * agent 如果不知道自己是哪种身份，就会把"权限不够"误判成"文件不存在"。
     */
    val shell: String = "unknown",
    val isRoot: Boolean = false,
)

/** The process boundary, factored out so tool logic is testable on a plain JVM with a fake. */
internal interface ShellRunner {
    fun exec(command: String, timeoutMillis: Long): RawShellResult
}

/** Per-stream capture ceiling: enough for any sane command, small enough to protect a turn. */
internal const val MAX_CAPTURE_BYTES = 256 * 1024

/**
 * Runs one command through the device's `su`.
 *
 * `su -c <command>` passes the command as a single argv element - no shell quoting games on the
 * way in. stdout and stderr are drained on their own threads because a process that fills either
 * pipe blocks until somebody reads it; a bounded capture keeps a `yes`-style flood from eating
 * the heap, and draining continues past the cap so the process itself never stalls on us.
 *
 * A first run may park inside `su` while Magisk shows its grant dialog: that surfaces as
 * [RawShellResult.timedOut], which is exactly why the tool layer always runs with a timeout.
 *
 * [shellBinary] is injectable for one reason: JVM unit tests point it at a real `sh` and exercise
 * this class end to end without a rooted phone.
 */
/**
 * 选择用哪个 shell：先试 root，不行退到应用自己的 shell。
 *
 * 之前只有 `SuShellRunner`，而 EV 的进程根本没有 root（设备上没有可用的 `su`），
 * 结果 `run_shell` 一条命令都跑不起来——连 `ls` 这种不需要特权的也不行。
 * 普通命令不需要 root，退到 `/system/bin/sh` 就能用；能拿到 root 的时候仍然优先用它。
 *
 * 探测只做一次并缓存：`su` 存在但未授权时进程会停在授权弹窗上，所以探测必须带超时，
 * 超时就当"没有 root"——这比每次调用都挂几秒强得多。
 */
internal class ProbingShellRunner(
    private val rootBinary: String = "su",
    private val plainBinary: String = "/system/bin/sh",
) : ShellRunner {

    private val delegate: ShellRunner by lazy {
        if (probeRoot()) ProcessShellRunner(rootBinary, isRoot = true)
        else ProcessShellRunner(plainBinary, isRoot = false)
    }

    override fun exec(command: String, timeoutMillis: Long): RawShellResult =
        delegate.exec(command, timeoutMillis)

    /** 真跑一条命令确认拿到的是 uid 0——只看 `su` 文件存在不够，它可能没被授权。 */
    private fun probeRoot(): Boolean = runCatching {
        val process = ProcessBuilder(rootBinary, "-c", "id").start()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        val out = process.inputStream.bufferedReader().readText()
        process.exitValue() == 0 && out.contains("uid=0")
    }.getOrDefault(false)
}

internal class ProcessShellRunner(
    private val shellBinary: String = "su",
    private val isRoot: Boolean = shellBinary == "su",
) : ShellRunner {

    override fun exec(command: String, timeoutMillis: Long): RawShellResult {
        val process = ProcessBuilder(shellBinary, "-c", command).start()
        val stdout = OutputDrain(process.inputStream, "shell-stdout")
        val stderr = OutputDrain(process.errorStream, "shell-stderr")
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            // destroy() first would leave a stubborn process holding the pipes; forcible kill,
            // then reap, then collect whatever the drains managed to capture.
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            stdout.join()
            stderr.join()
            return RawShellResult(
                exitCode = null,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = true,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
                shell = shellBinary,
                isRoot = isRoot,
            )
        }
        stdout.join()
        stderr.join()
        return RawShellResult(
            exitCode = process.exitValue(),
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = false,
            shell = shellBinary,
            isRoot = isRoot,
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated,
        )
    }

    /** Reads one stream to EOF, keeping at most [MAX_CAPTURE_BYTES]. */
    private class OutputDrain(stream: InputStream, name: String) {
        private val buffer = java.io.ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        private val thread = Thread({
            val chunk = ByteArray(8192)
            try {
                stream.use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (buffer.size() < MAX_CAPTURE_BYTES) {
                            val room = MAX_CAPTURE_BYTES - buffer.size()
                            buffer.write(chunk, 0, minOf(read, room))
                            if (read > room) truncated = true
                        } else {
                            truncated = true
                        }
                    }
                }
            } catch (_: Exception) {
                // A dying process closes its pipes mid-read: what was captured is what there is.
            }
        }).apply {
            isDaemon = true
            this.name = name
            start()
        }

        fun join() = runCatching { thread.join(5_000) }

        /** Malformed UTF-8 is replaced, never thrown: shell output is bytes, not a promise. */
        fun text(): String = String(buffer.toByteArray(), Charsets.UTF_8)
    }
}
