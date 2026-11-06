package ev.buan.hostcore.validation

import ev.buan.hostcore.dependency.DependencyCheck
import ev.buan.hostcore.dependency.PluginDependency
import ev.buan.hostcore.trust.PluginOrigin
import ev.buan.hostcore.trust.TrustDecision
import ev.buan.hostcore.trust.TrustGate
import ev.buan.hostcore.trust.TrustTier


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

    /** Container depends on a version of a plugin that is older than the one it declared. */
    DEPENDENCY_VERSION,

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
    /**
     * 容器从哪来：随包内置，还是外部投放目录。
     *
     * 默认 [PluginOrigin.EXTERNAL]——**默认值是"少信任"的那一侧**：忘了传的调用方会得到
     * 一次真正的判定，而不是一次免费的放行。
     */
    val origin: PluginOrigin = PluginOrigin.EXTERNAL,
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
    /**
     * Plugins accepted earlier in this same sync pass, id → version; dependencies must resolve here.
     *
     * 版本是后加的：规则要求"依赖声明版本，宿主在装配时检查"。以前只有 id 集合，
     * 于是"替换需版本满足"根本无法判定。
     */
    val acceptedPluginVersions: Map<String, String> = emptyMap(),
    /**
     * id -> the permission wire names the user granted that container. Default is empty, so a
     * container that declares capabilities fails closed until the user grants them.
     */
    val grantedPermissions: Map<String, Set<String>> = emptyMap(),
) {
    /** 只看"装没装"的地方继续用这个；它由 [acceptedPluginVersions] 推导，不再单独维护。 */
    val acceptedPluginIds: Set<String> get() = acceptedPluginVersions.keys
}

sealed interface ValidationResult {
    /**
     * 通过，并且带上信任判定。
     *
     * 判定跟"能不能装"是同一个决定的两面：把它留给调用方再算一遍，就会出现"装的时候
     * 算一次、跑的时候算另一次"的分裂，而这两次不一致时受苦的是用户（卡片说可信、
     * 激活门说不），所以它跟着结果一起走。
     */
    data class Accepted(val trust: TrustDecision) : ValidationResult

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
        val trust = decideTrust(candidate, policy)
        signatureGate(candidate, trust)?.let { return it }
        dependencyGate(candidate, policy)?.let { return it }
        apiGate(candidate, policy)?.let { return it }
        contentGate(candidate)?.let { return it }
        identityGate(candidate, policy)?.let { return it }
        permissionGate(candidate, policy)?.let { return it }
        return ValidationResult.Accepted(trust)
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
        /**
         * 同 [PluginCandidate.origin]，默认外部：加载期能拿到的事实只会比安装期少，
         * 所以默认必须是少信任的那一侧。
         */
        origin: PluginOrigin = PluginOrigin.EXTERNAL,
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
        val trust = TrustGate.decide(
            origin = origin,
            signing = signing,
            sha256 = sha256,
            approvedSha256 = policy.approvedUnsignedHashes[id],
            hostCertificateSha256 = policy.hostCertificateSha256,
        )
        if (trust.tier == TrustTier.UNTRUSTED) {
            // 两种失败分开报：没签名是"没批过这批字节"，签了但不是我们的
            // 是"这个文件不该在这里"。卡片上的下一步不一样。
            val unsigned = signing is SigningState.Unsigned
            return ValidationResult.Rejected(
                PluginRejection(
                    if (unsigned) PluginRejectionCode.UNSIGNED_NOT_APPROVED
                    else PluginRejectionCode.SIGNATURE_HOST_MISMATCH,
                    id,
                    if (unsigned) "未签名插件未获批准（或哈希已变更），拒绝加载"
                    else "加载期签名与宿主不一致，拒绝加载",
                    trust.reason,
                ),
            )
        }
        return permissionRejection(id, requestedPermissions, policy)
            ?: ValidationResult.Accepted(trust)
    }

    /**
     * 信任门：只问一件事——这批字节在设计上是"可信"、"受限"还是"不可信"。
     *
     * 判定的全部输入都在 [PluginCandidate] 和 [HostPolicy] 里，算法在 `TrustGate`（纯函数）。
     * 这一层不重复实现它，否则安装期的档位和加载期的档位会各自漂移。
     */
    private fun signatureGate(
        candidate: PluginCandidate,
        trust: TrustDecision,
    ): ValidationResult? {
        if (trust.tier != TrustTier.UNTRUSTED) return null
        val signing = candidate.signing
        // 没有 Android 签名不等于"签名不对"：jar / dex 根本装不下签名，这两类容器的
        // 唯一出路是人确认这批字节。签坏了的 APK 才是拒绝。
        if (signing is SigningState.Unsigned) {
            return ValidationResult.NeedsUnsignedApproval(candidate.id, candidate.sha256)
        }
        val empty = (signing as SigningState.Signed).certificateSha256.isEmpty()
        return ValidationResult.Rejected(
            PluginRejection(
                if (empty) PluginRejectionCode.SIGNATURE_EMPTY else PluginRejectionCode.SIGNATURE_HOST_MISMATCH,
                candidate.id,
                if (empty) "插件没有签名证书" else "插件签名与宿主不一致",
                trust.reason,
            ),
        )
    }

    private fun decideTrust(candidate: PluginCandidate, policy: HostPolicy): TrustDecision =
        TrustGate.decide(
            origin = candidate.origin,
            signing = candidate.signing,
            sha256 = candidate.sha256,
            approvedSha256 = policy.approvedUnsignedHashes[candidate.id],
            hostCertificateSha256 = policy.hostCertificateSha256,
        )

    /**
     * A dependency must already be accepted. The loader can resolve classes from a dependency
     * loader, but only if that module exists; without this check the failure surfaces later as a
     * ClassNotFoundException inside the plugin, far from the actual cause.
     */
    private fun dependencyGate(candidate: PluginCandidate, policy: HostPolicy): ValidationResult? {
        if (candidate.dependencies.isEmpty()) return null
        val declared = candidate.dependencies.mapNotNull(PluginDependency::parse)
        // 解析不出来的条目（`id@latest` 这种）当成"没声明版本"处理，而不是静默丢弃：
        // 丢掉会让一条写坏的依赖变成"没有依赖"，那正是这一层要防的事。
        val unparsable = candidate.dependencies.filter { PluginDependency.parse(it) == null }
        if (unparsable.isNotEmpty()) {
            return ValidationResult.Rejected(
                PluginRejection(
                    PluginRejectionCode.DEPENDENCY_MISSING,
                    candidate.id,
                    "依赖声明无法解析：${unparsable.joinToString(", ")}（版本段必须是数字，如 1.2.0）",
                ),
            )
        }
        val failures = DependencyCheck.check(declared, policy.acceptedPluginVersions)
        if (failures.isEmpty()) return null
        val tooOld = failures.filter { it.versionMismatch }
        return ValidationResult.Rejected(
            PluginRejection(
                // 两种失败分开报：一个是"没装"，处理是去装；一个是"太旧"，处理是升级。
                if (tooOld.isNotEmpty()) PluginRejectionCode.DEPENDENCY_VERSION else PluginRejectionCode.DEPENDENCY_MISSING,
                candidate.id,
                failures.joinToString("；") { it.message(candidate.id) },
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
