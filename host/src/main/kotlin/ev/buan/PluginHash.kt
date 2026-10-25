package ev.buan

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * SHA-256 of one plugin container, measured from the bytes on disk right now.
 *
 * The kernel loader owns its own copy of this hash for the descriptor pin, but it only hands the
 * host a descriptor, so the host measures the file itself when it needs the value: sync records it
 * as the installed identity, and the load-time verifier re-checks the bytes against the approval
 * before a plugin is allowed in.
 */
internal fun androidModuleHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
