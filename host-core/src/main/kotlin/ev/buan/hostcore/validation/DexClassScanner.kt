package ev.buan.hostcore.validation

import java.io.File
import java.util.zip.ZipFile

/**
 * Minimal dex reader: lists the classes a container *defines*, not the ones it references.
 *
 * The distinction matters. A plugin always references `kotlin.*`, `androidx.compose.*` and the
 * host API; that is expected and shared through the class loader. What it must never do is carry
 * a second definition of a class that the host or the platform already owns - the child-first
 * loader would then load the plugin's copy for the plugin and the host's copy for everyone else,
 * and the two identities silently disagree.
 */
object DexClassScanner {

    data class Violation(val entry: String, val className: String)

    private data class DefinedClass(val name: String, val synthetic: Boolean)

    private val DEX_ENTRY = Regex("classes\\d*\\.dex")

    fun violations(file: File, forbiddenPrefixes: Collection<String>): List<Violation> {
        val prefixes = forbiddenPrefixes.map { it.replace('.', '/').trimEnd('/') + "/" }
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX_ENTRY.matches(it.name) }
                    .flatMap { entry ->
                        zip.getInputStream(entry).readBytes().let { bytes ->
                            defined(bytes).asSequence().map { entry.name to it }
                        }
                    }
                    .filter { (_, defined) ->
                        // AGP emits synthetic placeholder interfaces for platform types that are
                        // visible at compile time but provided by the OS at runtime. They carry no
                        // members and cannot split a class identity, so they are not duplicates.
                        !defined.synthetic && prefixes.any { defined.name.replace('.', '/').startsWith(it) }
                    }
                    .map { (entry, defined) -> Violation(entry, defined.name) }
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    /** True when [className] sits inside one of [forbiddenPrefixes] (dot form). */
    fun isReserved(className: String, forbiddenPrefixes: Collection<String>): Boolean =
        forbiddenPrefixes.any { pkg ->
            className == pkg || className.startsWith("$pkg.")
        }

    /** Classes one dex image defines. Malformed input yields nothing. */
    fun definedClasses(dex: ByteArray): List<String> = defined(dex).map { it.name }

    private fun defined(dex: ByteArray): List<DefinedClass> {
        if (dex.size < HEADER_SIZE) return emptyList()
        if (dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) {
            return emptyList()
        }
        return runCatching {
            val stringIdsOff = u32(dex, 0x3C)
            val typeIdsOff = u32(dex, 0x44)
            val classDefsSize = u32(dex, 0x60)
            val classDefsOff = u32(dex, 0x64)
            val names = ArrayList<DefinedClass>(classDefsSize.coerceAtMost(4096))
            for (i in 0 until classDefsSize) {
                val base = classDefsOff + i * CLASS_DEF_SIZE
                val classIdx = u32(dex, base)
                val flags = u32(dex, base + 4)
                val descriptorIdx = u32(dex, typeIdsOff + classIdx * 4)
                val descriptor = readString(dex, u32(dex, stringIdsOff + descriptorIdx * 4))
                names += DefinedClass(descriptorToClass(descriptor), flags and ACC_SYNTHETIC != 0)
            }
            names
        }.getOrDefault(emptyList())
    }

    private fun descriptorToClass(descriptor: String): String {
        val inner = descriptor.removePrefix("L").removeSuffix(";")
        return inner.replace('/', '.')
    }

    private fun readString(dex: ByteArray, dataOffset: Int): String {
        var cursor = dataOffset
        // Skip the uleb128 UTF-16 length prefix; the payload is NUL terminated.
        while (true) {
            val byte = dex[cursor].toInt() and 0xFF
            cursor++
            if (byte and 0x80 == 0) break
        }
        val start = cursor
        while (cursor < dex.size && dex[cursor] != 0.toByte()) cursor++
        return String(dex, start, cursor - start, Charsets.UTF_8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private const val HEADER_SIZE = 0x70
    private const val CLASS_DEF_SIZE = 32
    private const val ACC_SYNTHETIC = 0x1000
}
