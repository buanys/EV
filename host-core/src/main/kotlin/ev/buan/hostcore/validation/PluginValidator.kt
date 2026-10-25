package ev.buan.hostcore.validation


/**
 * Every reason a container may not run. Stable codes, not message text: the UI branches on the
 * code, tests assert on it, and the message is free to change.
 */
enum class PluginRejectionCode {
    /** APK carries no signing certificate at all. */
    SIGNATURE_EMPTY,

    /** APK is signed, but by a key the host does not trust. */
    SIGNATURE_HOST_MISMATCH,

    /** Container was compiled against a different contract revision. */
    API_VERSION_MISMATCH,

    /** Container defines a class that the platform or the host already owns. */
    DUPLICATE_PLATFORM_CLASS,

    /** Two different payloads claim the same plugin id. */
    ID_HASH_CONFLICT,

    /** Load time: the file on disk no longer matches the hash accepted during sync. */
    LOAD_HASH_MISMATCH,

    /** Load time: unsigned container whose exact hash the user never approved. */
    UNSIGNED_NOT_APPROVED,

    /** Container depends on a plugin id that has not been accepted in this run. */
    DEPENDENCY_MISSING,

    /**
     * Container is part of a dependency cycle, so no accept order exists for the group.
     *
     * Detected while ordering the sync pass (host-core `DependencyResolver`), not by this validator:
     * the validator only sees one candidate at a time.
     */
    DEPENDENCY_CYCLE,

    /** Container asks for a host permission the user has not granted this id. */
    PERMISSION_DENIED,
}

/** What the host decided about one container, with a code the caller can act on. */
data class PluginRejection(
    val code: PluginRejectionCode,
    val pluginId: String,
    /** User facing, already localised. */
    val message: String,
    /** Machine detail for logs; may be null. */
    val detail: String? = null,
)

sealed interface SigningState {
    data class Signed(val certificateSha256: Set<String>) : SigningState

    /** jar / dex containers cannot carry an Android signature. */
    data object Unsigned : SigningState
}

/** Everything the validator needs, gathered by the Android layer. */
data class PluginCandidate(
    val id: String,
    val version: String,
    val entryClass: String,
    val apiVersion: Int,
    val sha256: String,
    val signing: SigningState,
    /** Classes this container defines inside a reserved package, already measured. */
    val platformClassViolations: List<String> = emptyList(),
    /** Plugin ids this container links against at runtime. */
    val dependencies: List<String> = emptyList(),
    /**
     * Host capability **wire names** the container declared in its metadata. Empty for a container
     * that declares nothing, which is the common case and grants no capability at all.
     *
     * Strings, not `org.cordis.api.HostPermission`: this module is the host's pure-JVM logic and
     * does not depend on the plugin contract. The host maps names to its permission type at the
     * boundary (metadata parsing, activation gate) and only the comparison happens here.
     */
    val requestedPermissions: Set<String> = emptySet(),
)

/** Host-side decisions the validator must not own. */
data class HostPolicy(
    val hostCertificateSha256: Set<String>,
    val supportedApiVersion: Int,
    /** id -> the exact sha256 the user approved for an unsigned container. */
    val approvedUnsignedHashes: Map<String, String> = emptyMap(),
    /** id -> sha256 already accepted in this run, used for conflict detection. */
    val installedHashes: Map<String, String> = emptyMap(),
    /** Plugin ids accepted earlier in this same sync pass; dependencies must resolve here. */
    val acceptedPluginIds: Set<String> = emptySet(),
    /**
     * id -> the permission wire names the user granted that container. Default is empty, so a
     * container that declares capabilities fails closed until the user grants them.
     */
    val grantedPermissions: Map<String, Set<String>> = emptyMap(),
)

sealed interface ValidationResult {
    data object Accepted : ValidationResult

    data class Rejected(val rejection: PluginRejection) : ValidationResult

    /** Unsigned container: the host may run it only after the user approves this exact hash. */
    data class NeedsUnsignedApproval(val pluginId: String, val sha256: String) : ValidationResult
}

/**
 * The single gate for every container.
 *
 * Deliberately pure Kotlin: no Context, no File, no ZipFile, and no dependency on the plugin
 * contract (`:plugin-api`). Reading certificates, hashing and scanning dex all happen in the
 * Android layer and arrive as a [PluginCandidate]; permission declarations arrive as wire-name
 * strings. That keeps the decision unit-testable on the JVM and out of the published SDK surface.
 *
 * Order matters: identity before compatibility before content. A container signed by a stranger is
 * rejected without us ever parsing its classes; a container built for another API is rejected
 * before its permission declarations are taken literally.
 *
 * The permission gate runs last on purpose: install-time callers may treat `PERMISSION_DENIED` as
 * "install but do not activate yet" (the host needs the plugin visible to let the user grant it), and
 * that is only safe when every other gate already passed.
 */
object PluginValidator {

    fun validate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult {
        signatureGate(candidate, policy)?.let { return it }
        dependencyGate(candidate, policy)?.let { return it }
        apiGate(candidate, policy)?.let { return it }
        contentGate(candidate)?.let { return it }
        identityGate(candidate, policy)?.let { return it }
        permissionGate(candidate, policy)?.let { return it }
        return ValidationResult.Accepted
    }

    /**
     * Load-time re-check.
     *
     * Sync decides what may be installed; this decides what may still be loaded after the fact.
     * A file can be swapped between the two, and private storage being read-only is a convention,
     * not a guarantee against a root user or a bug. [sha256] is measured from the file right now.
     *
     * [requestedPermissions] re-checks the user's grants at load time: a revoke between activation
     * and load (or a loader that bypassed the host's activation gate) must not run the plugin. The
     * signature/hash checks come first, so a swapped file is still reported as a hash mismatch.
     */
    fun verifyAtLoad(
        id: String,
        sha256: String,
        signing: SigningState,
        policy: HostPolicy,
        requestedPermissions: Set<String> = emptySet(),
    ): ValidationResult {
        val expected = policy.installedHashes[id]
            ?: return ValidationResult.Rejected(
                PluginRejection(
                    PluginRejectionCode.LOAD_HASH_MISMATCH,
                    id,
                    "加载期缺少已批准的哈希记录，拒绝加载",
                ),
            )
        if (!expected.equals(sha256, ignoreCase = true)) {
            return ValidationResult.Rejected(
                PluginRejection(
                    PluginRejectionCode.LOAD_HASH_MISMATCH,
                    id,
                    "插件文件在同步之后被替换（批准 ${expected.take(8)}… / 实际 ${sha256.take(8)}…），拒绝加载",
                ),
            )
        }
        val signatureDecision = when (signing) {
            is SigningState.Signed -> if (signing.certificateSha256.any { it in policy.hostCertificateSha256 }) {
                ValidationResult.Accepted
            } else {
                ValidationResult.Rejected(
                    PluginRejection(
                        PluginRejectionCode.SIGNATURE_HOST_MISMATCH,
                        id,
                        "加载期签名与宿主不一致，拒绝加载",
                    ),
                )
            }

            SigningState.Unsigned -> if (policy.approvedUnsignedHashes[id] == sha256) {
                ValidationResult.Accepted
            } else {
                ValidationResult.Rejected(
                    PluginRejection(
                        PluginRejectionCode.UNSIGNED_NOT_APPROVED,
                        id,
                        "未签名插件未获批准（或哈希已变更），拒绝加载",
                    ),
                )
            }
        }
        if (signatureDecision is ValidationResult.Rejected) return signatureDecision
        return permissionRejection(id, requestedPermissions, policy) ?: signatureDecision
    }

    private fun signatureGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? =
        when (val signing = candidate.signing) {
            is SigningState.Signed -> when {
                signing.certificateSha256.isEmpty() -> ValidationResult.Rejected(
                    PluginRejection(
                        PluginRejectionCode.SIGNATURE_EMPTY,
                        candidate.id,
                        "插件没有签名证书",
                    ),
                )

                signing.certificateSha256.none { it in policy.hostCertificateSha256 } -> ValidationResult.Rejected(
                    PluginRejection(
                        PluginRejectionCode.SIGNATURE_HOST_MISMATCH,
                        candidate.id,
                        "插件签名与宿主不一致",
                        "证书 ${signing.certificateSha256.first().take(16)}… 不在宿主信任列表",
                    ),
                )

                else -> null
            }

            SigningState.Unsigned -> {
                val approved = policy.approvedUnsignedHashes[candidate.id]
                if (approved == candidate.sha256) {
                    null
                } else {
                    // First sight, or the bytes changed since approval: ask again.
                    ValidationResult.NeedsUnsignedApproval(candidate.id, candidate.sha256)
                }
            }
        }

    /**
     * A dependency must already be accepted. The loader can resolve classes from a dependency
     * loader, but only if that module exists; without this check the failure surfaces later as a
     * ClassNotFoundException inside the plugin, far from the actual cause.
     */
    private fun dependencyGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? {
        if (candidate.dependencies.isEmpty()) return null
        val missing = candidate.dependencies.filter { it !in policy.acceptedPluginIds }
        if (missing.isEmpty()) return null
        return ValidationResult.Rejected(
            PluginRejection(
                PluginRejectionCode.DEPENDENCY_MISSING,
                candidate.id,
                "缺少依赖插件：${missing.joinToString(", ")}（必须先被接受或同时存在）",
            ),
        )
    }

    private fun apiGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? =
        if (candidate.apiVersion == policy.supportedApiVersion) {
            null
        } else {
            ValidationResult.Rejected(
                PluginRejection(
                    PluginRejectionCode.API_VERSION_MISMATCH,
                    candidate.id,
                    "API 版本不兼容（插件 ${candidate.apiVersion}，宿主 ${policy.supportedApiVersion}）",
                ),
            )
        }

    /**
     * A declared capability must be granted to this exact id. Default-deny: the policy starts
     * empty, so a fresh container that declares anything is rejected until the user grants it.
     *
     * This gates host services only. It is not an OS sandbox: a plugin shares the host UID and can
     * still call Android directly (ADR-0003). What it cannot do is use a host-mediated service it
     * never declared or the user never granted.
     *
     * Shared by the install pass, the load-time re-check and (via the same sets) the activation gate
     * in the host, so all three agree on when a plugin may use a declared capability.
     */
    private fun permissionGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? =
        permissionRejection(candidate.id, candidate.requestedPermissions, policy)

    private fun permissionRejection(
        id: String,
        requested: Set<String>,
        policy: HostPolicy,
    ): ValidationResult? {
        if (requested.isEmpty()) return null
        val granted = policy.grantedPermissions[id].orEmpty()
        val missing = requested.filterNot { it in granted }
        if (missing.isEmpty()) return null
        return ValidationResult.Rejected(
            PluginRejection(
                PluginRejectionCode.PERMISSION_DENIED,
                id,
                "缺少权限：${missing.joinToString(", ")}（可在插件管理页授予）",
            ),
        )
    }

    private fun contentGate(candidate: PluginCandidate): ValidationResult? {
        val violations = candidate.platformClassViolations
        if (violations.isEmpty()) return null
        val sample = violations.take(3).joinToString(", ")
        return ValidationResult.Rejected(
            PluginRejection(
                PluginRejectionCode.DUPLICATE_PLATFORM_CLASS,
                candidate.id,
                "插件包内出现宿主/系统类定义 → $sample（共 ${violations.size} 个）",
            ),
        )
    }

    private fun identityGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? {
        val installed = policy.installedHashes[candidate.id] ?: return null
        if (installed == candidate.sha256) return null
        return ValidationResult.Rejected(
            PluginRejection(
                PluginRejectionCode.ID_HASH_CONFLICT,
                candidate.id,
                "id 冲突：两份内容不同的插件（${installed.take(8)}… / ${candidate.sha256.take(8)}…）",
            ),
        )
    }
}
