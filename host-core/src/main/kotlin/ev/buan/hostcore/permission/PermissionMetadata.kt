package ev.buan.hostcore.permission

/**
 * One container's permission declaration, split by whether this host understands the name.
 *
 * Both sides matter: [declared] feeds the validator's permission gate, [unknown] is a hard
 * rejection. A container that asks for a capability this build has never heard of must fail
 * visibly instead of silently running without it - the failure belongs to the metadata, not to
 * the first API call that needed the permission.
 */
data class PermissionDeclaration(
    /** Names the host knows, in declaration order, de-duplicated. */
    val declared: Set<String> = emptySet(),
    /** Names the host does not know; [isWellFormed] is false when this is non-empty. */
    val unknown: Set<String> = emptySet(),
) {
    /** True when every declared name is a permission this host understands. */
    val isWellFormed: Boolean
        get() = unknown.isEmpty()
}

/**
 * Parses the two metadata shapes plugins use into a [PermissionDeclaration].
 *
 * Pure strings on purpose: reading an APK manifest or a sidecar file is Android work, so the
 * host layer extracts the raw value and this module decides what it means. Keeping the vocabulary
 * out of this module means the caller supplies it (the host passes `HostPermission.wireNames`),
 * so `:host-core` stays free of the plugin contract.
 */
object PermissionMetadataParser {

    /** APK `<meta-data>` key: a comma-separated string, e.g. `"NETWORK,PLUGIN_STORAGE"`. */
    const val APK_METADATA_KEY: String = "cordis.permissions"

    /** jar/dex sidecar key: a JSON array, e.g. `"permissions": ["NETWORK"]`. */
    const val JAR_SIDECAR_KEY: String = "permissions"

    /** Missing or blank metadata means "no permissions declared", never "all permissions". */
    fun parseApkMetadata(raw: String?, supported: Set<String>): PermissionDeclaration =
        classify(raw?.split(',') ?: emptyList(), supported)

    /** A missing sidecar array means "no permissions declared"; blank entries are ignored. */
    fun parseJarMetadata(declared: List<String>?, supported: Set<String>): PermissionDeclaration =
        classify(declared ?: emptyList(), supported)

    private fun classify(tokens: Iterable<String>, supported: Set<String>): PermissionDeclaration {
        val declared = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()
        tokens.forEach { token ->
            val name = token.trim()
            if (name.isEmpty()) return@forEach
            if (name in supported) declared += name else unknown += name
        }
        return PermissionDeclaration(declared, unknown)
    }
}
