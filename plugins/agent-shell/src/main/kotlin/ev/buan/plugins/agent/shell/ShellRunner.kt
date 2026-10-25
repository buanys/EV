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
internal class SuShellRunner(private val shellBinary: String = "su") : ShellRunner {

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
            )
        }
        stdout.join()
        stderr.join()
        return RawShellResult(
            exitCode = process.exitValue(),
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = false,
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
