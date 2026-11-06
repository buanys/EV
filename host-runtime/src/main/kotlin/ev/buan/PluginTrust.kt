package ev.buan

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Trust policy for plugin artifacts.
 *
 * An APK must be signed by the same certificate set as the host. Debug builds share one debug
 * key, so everything built on this machine passes; a foreign APK dropped into `EV/mods` does not.
 *
 * `.jar` / `.dex` containers carry no Android signature at all. They are accepted only while
 * Unsigned containers are reported as [SigningState.Unsigned]; whether they may run is decided
 * by PluginValidator, never here.
 */
class PluginTrust(private val context: Context) {

    private val hostCertificates: Set<String> by lazy { ownCertificates() }

    /** Host certificate digests; the validator decides, this only reports. */
    val hostCertificateHashes: Set<String> get() = hostCertificates

    /**
     * Signing facts about one container, without deciding anything.
     * `null` certificates means "no Android signature" (jar/dex), not "invalid APK".
     */
    fun signingState(file: File): ev.buan.hostcore.validation.SigningState {
        val certificates = archiveCertificates(file)
        return if (certificates == null) {
            ev.buan.hostcore.validation.SigningState.Unsigned
        } else {
            ev.buan.hostcore.validation.SigningState.Signed(certificates)
        }
    }

    private fun archiveCertificates(file: File): Set<String>? {
        if (!file.isFile) return null
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(
                file.path,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return null
        val signers = runCatching { info.signingInfo?.apkContentsSigners }.getOrNull() ?: return emptySet()
        return signers.mapTo(linkedSetOf()) { certificateDigest(it.toByteArray()) }
    }

    private fun ownCertificates(): Set<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        signers.mapTo(linkedSetOf()) { certificateDigest(it.toByteArray()) }
    }.getOrElse { emptySet() }

    private fun certificateDigest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
