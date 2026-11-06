package ev.buan.hostcore.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One passing and one failing case per gate. The validator is pure, so these run as plain JVM
 * tests with no Android, no file system and no dex.
 */
class PluginValidatorTest {

    private val hostCert = "aa".repeat(32)
    private val policy = HostPolicy(
        hostCertificateSha256 = setOf(hostCert),
        supportedApiVersion = 1,
    )

    private fun candidate(
        id: String = "plugin.test",
        apiVersion: Int = 1,
        sha256: String = "11".repeat(32),
        signing: SigningState = SigningState.Signed(setOf(hostCert)),
        violations: List<String> = emptyList(),
        dependencies: List<String> = emptyList(),
        permissions: Set<String> = emptySet(),
        origin: ev.buan.hostcore.trust.PluginOrigin = ev.buan.hostcore.trust.PluginOrigin.EXTERNAL,
    ) = PluginCandidate(
        id = id,
        version = "1.0.0",
        entryClass = "test.Plugin",
        apiVersion = apiVersion,
        sha256 = sha256,
        origin = origin,
        signing = signing,
        platformClassViolations = violations,
        dependencies = dependencies,
        requestedPermissions = permissions,
    )

    private fun rejectionOf(result: ValidationResult): PluginRejection =
        assertIs<ValidationResult.Rejected>(result).rejection

    // ---- trust tier ---------------------------------------------------------

    @Test
    fun `内置容器不需要批准就是可信`() {
        val result = PluginValidator.validate(
            candidate(origin = ev.buan.hostcore.trust.PluginOrigin.BUILT_IN, signing = SigningState.Unsigned),
            policy,
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(ev.buan.hostcore.trust.TrustTier.TRUSTED, accepted.trust.tier)
    }

    @Test
    fun `批准过的未签名容器是受限`() {
        val hash = "22".repeat(32)
        val result = PluginValidator.validate(
            candidate(sha256 = hash, signing = SigningState.Unsigned),
            policy.copy(approvedUnsignedHashes = mapOf("plugin.test" to hash)),
        )
        val accepted = assertIs<ValidationResult.Accepted>(result)
        assertEquals(ev.buan.hostcore.trust.TrustTier.RESTRICTED, accepted.trust.tier)
    }

    @Test
    fun `同签名走的是可信而不是受限`() {
        val accepted = assertIs<ValidationResult.Accepted>(PluginValidator.validate(candidate(), policy))
        assertEquals(ev.buan.hostcore.trust.TrustTier.TRUSTED, accepted.trust.tier)
    }

    @Test
    fun `未批准的内置签名容器仍然可信`() {
        // 内置容器的完整性由 APK 签名担保：它自己的证书是谁的并不改变这一点。
        val result = PluginValidator.validate(
            candidate(
                origin = ev.buan.hostcore.trust.PluginOrigin.BUILT_IN,
                signing = SigningState.Signed(setOf("bb".repeat(32))),
            ),
            policy,
        )
        assertTrue(result is ValidationResult.Accepted)
    }

    // ---- signature ----------------------------------------------------------

    @Test
    fun `同签名通过`() {
        assertTrue((PluginValidator.validate(candidate(), policy)) is ValidationResult.Accepted)
    }

    @Test
    fun `不同签名被拒`() {
        val foreign = SigningState.Signed(setOf("bb".repeat(32)))
        val rejection = rejectionOf(PluginValidator.validate(candidate(signing = foreign), policy))
        assertEquals(PluginRejectionCode.SIGNATURE_HOST_MISMATCH, rejection.code)
    }

    @Test
    fun `签名证书为空被拒`() {
        val empty = SigningState.Signed(emptySet())
        val rejection = rejectionOf(PluginValidator.validate(candidate(signing = empty), policy))
        assertEquals(PluginRejectionCode.SIGNATURE_EMPTY, rejection.code)
    }

    // ---- unsigned approval --------------------------------------------------

    @Test
    fun `未签名首次出现需要确认`() {
        val result = PluginValidator.validate(candidate(signing = SigningState.Unsigned), policy)
        val approval = assertIs<ValidationResult.NeedsUnsignedApproval>(result)
        assertEquals("11".repeat(32), approval.sha256)
    }

    @Test
    fun `未签名且已确认同一哈希则通过`() {
        val approved = policy.copy(
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
        )
        val result = PluginValidator.validate(candidate(signing = SigningState.Unsigned), approved)
        assertTrue((result) is ValidationResult.Accepted)
    }

    @Test
    fun `未签名确认后哈希改变必须重新确认`() {
        val approved = policy.copy(
            approvedUnsignedHashes = mapOf("plugin.test" to "99".repeat(32)),
        )
        val result = PluginValidator.validate(candidate(signing = SigningState.Unsigned), approved)
        assertIs<ValidationResult.NeedsUnsignedApproval>(result)
    }

    // ---- api version -------------------------------------------------------

    @Test
    fun `API 版本一致通过`() {
        assertTrue((PluginValidator.validate(candidate(apiVersion = 1), policy)) is ValidationResult.Accepted)
    }

    @Test
    fun `API 版本不一致被拒`() {
        val rejection = rejectionOf(PluginValidator.validate(candidate(apiVersion = 2), policy))
        assertEquals(PluginRejectionCode.API_VERSION_MISMATCH, rejection.code)
    }

    // ---- host permissions (D3: API-level gate, not a sandbox) ---------------

    @Test
    fun `声明的权限全部授予则通过`() {
        val granted = policy.copy(
            grantedPermissions = mapOf(
                "plugin.test" to setOf("NETWORK", "PLUGIN_STORAGE"),
            ),
        )
        val result = PluginValidator.validate(
            candidate(permissions = setOf("NETWORK", "PLUGIN_STORAGE")),
            granted,
        )
        assertTrue((result) is ValidationResult.Accepted)
    }

    @Test
    fun `未声明权限的插件不需要授予就通过`() {
        assertTrue((PluginValidator.validate(candidate(), policy)) is ValidationResult.Accepted)
    }

    @Test
    fun `缺少权限被拒并给出 PERMISSION_DENIED`() {
        val granted = policy.copy(
            grantedPermissions = mapOf("plugin.test" to setOf("NETWORK")),
        )
        val rejection = rejectionOf(
            PluginValidator.validate(
                candidate(permissions = setOf("NETWORK", "BACKGROUND")),
                granted,
            ),
        )
        assertEquals(PluginRejectionCode.PERMISSION_DENIED, rejection.code)
        assertTrue(rejection.message.contains("BACKGROUND"), rejection.message)
        assertFalse(rejection.message.contains("NETWORK"), rejection.message)
    }

    @Test
    fun `默认授予表为空所以声明即被拒`() {
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(permissions = setOf("CLIPBOARD")), policy),
        )
        assertEquals(PluginRejectionCode.PERMISSION_DENIED, rejection.code)
    }

    @Test
    fun `授予表按插件 id 隔离`() {
        val granted = policy.copy(
            grantedPermissions = mapOf("plugin.other" to setOf("NETWORK", "PLUGIN_STORAGE", "BACKGROUND", "NOTIFICATIONS", "CLIPBOARD", "INSTALL_PACKAGES")),
        )
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(permissions = setOf("NETWORK")), granted),
        )
        assertEquals(PluginRejectionCode.PERMISSION_DENIED, rejection.code)
    }

    @Test
    fun `坏签名先于权限被拒`() {
        val foreign = SigningState.Signed(setOf("bb".repeat(32)))
        val rejection = rejectionOf(
            PluginValidator.validate(
                candidate(signing = foreign, permissions = setOf("INSTALL_PACKAGES")),
                policy,
            ),
        )
        assertEquals(PluginRejectionCode.SIGNATURE_HOST_MISMATCH, rejection.code)
    }

    @Test
    fun `权限门排在内容与身份门之后`() {
        // Install treats "permission missing" as "install but do not activate"; that is only safe
        // because every trust/content/identity gate runs before the permission gate.
        val withInstalled = policy.copy(installedHashes = mapOf("plugin.test" to "22".repeat(32)))
        val rejection = rejectionOf(
            PluginValidator.validate(
                candidate(
                    permissions = setOf("NETWORK"),
                    violations = listOf("org.json.JSONObject"),
                ),
                withInstalled,
            ),
        )
        assertEquals(PluginRejectionCode.DUPLICATE_PLATFORM_CLASS, rejection.code)
    }

    // ---- duplicate platform classes ----------------------------------------

    @Test
    fun `无平台类重复通过`() {
        assertTrue((PluginValidator.validate(candidate(violations = emptyList()), policy)) is ValidationResult.Accepted)
    }

    @Test
    fun `定义了平台类被拒`() {
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(violations = listOf("org.json.JSONObject")), policy),
        )
        assertEquals(PluginRejectionCode.DUPLICATE_PLATFORM_CLASS, rejection.code)
        assertEquals(true, rejection.message.contains("org.json.JSONObject"))
    }

    // ---- identity / hash conflict ------------------------------------------

    @Test
    fun `同 id 同哈希通过`() {
        val withInstalled = policy.copy(installedHashes = mapOf("plugin.test" to "11".repeat(32)))
        assertTrue((PluginValidator.validate(candidate(), withInstalled)) is ValidationResult.Accepted)
    }

    @Test
    fun `同 id 不同哈希被拒`() {
        val withInstalled = policy.copy(installedHashes = mapOf("plugin.test" to "22".repeat(32)))
        val rejection = rejectionOf(PluginValidator.validate(candidate(), withInstalled))
        assertEquals(PluginRejectionCode.ID_HASH_CONFLICT, rejection.code)
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    fun `坏签名先于 API 版本被拒`() {
        val foreign = SigningState.Signed(setOf("bb".repeat(32)))
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(apiVersion = 9, signing = foreign), policy),
        )
        assertEquals(PluginRejectionCode.SIGNATURE_HOST_MISMATCH, rejection.code)
    }

    @Test
    fun `确认态没有拒绝对象`() {
        val result = PluginValidator.validate(candidate(signing = SigningState.Unsigned), policy)
        assertNull((result as? ValidationResult.Rejected)?.rejection)
    }

    // ---- load time re-check -------------------------------------------------

    @Test
    fun `加载期哈希一致且已批准则通过`() {
        val installed = policy.copy(
            installedHashes = mapOf("plugin.test" to "11".repeat(32)),
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
        )
        val result = PluginValidator.verifyAtLoad(
            "plugin.test", "11".repeat(32), SigningState.Unsigned, installed,
        )
        assertTrue((result) is ValidationResult.Accepted)
    }

    @Test
    fun `加载期文件被替换必须拒载`() {
        val installed = policy.copy(
            installedHashes = mapOf("plugin.test" to "11".repeat(32)),
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
        )
        val rejection = rejectionOf(
            PluginValidator.verifyAtLoad("plugin.test", "22".repeat(32), SigningState.Unsigned, installed),
        )
        assertEquals(PluginRejectionCode.LOAD_HASH_MISMATCH, rejection.code)
    }

    @Test
    fun `加载期未签名未批准必须拒载`() {
        val installed = policy.copy(installedHashes = mapOf("plugin.test" to "11".repeat(32)))
        val rejection = rejectionOf(
            PluginValidator.verifyAtLoad("plugin.test", "11".repeat(32), SigningState.Unsigned, installed),
        )
        assertEquals(PluginRejectionCode.UNSIGNED_NOT_APPROVED, rejection.code)
    }

    @Test
    fun `加载期签名被换必须拒载`() {
        val installed = policy.copy(installedHashes = mapOf("plugin.test" to "11".repeat(32)))
        val rejection = rejectionOf(
            PluginValidator.verifyAtLoad(
                "plugin.test", "11".repeat(32),
                SigningState.Signed(setOf("bb".repeat(32))), installed,
            ),
        )
        assertEquals(PluginRejectionCode.SIGNATURE_HOST_MISMATCH, rejection.code)
    }

    @Test
    fun `加载期权限已授予则通过`() {
        val installed = policy.copy(
            installedHashes = mapOf("plugin.test" to "11".repeat(32)),
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
            grantedPermissions = mapOf("plugin.test" to setOf("PLUGIN_STORAGE")),
        )
        val result = PluginValidator.verifyAtLoad(
            "plugin.test", "11".repeat(32), SigningState.Unsigned, installed,
            requestedPermissions = setOf("PLUGIN_STORAGE"),
        )
        assertTrue((result) is ValidationResult.Accepted)
    }

    @Test
    fun `加载期权限已撤销则拒载并给出 PERMISSION_DENIED`() {
        val installed = policy.copy(
            installedHashes = mapOf("plugin.test" to "11".repeat(32)),
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
        )
        val rejection = rejectionOf(
            PluginValidator.verifyAtLoad(
                "plugin.test", "11".repeat(32), SigningState.Unsigned, installed,
                requestedPermissions = setOf("PLUGIN_STORAGE"),
            ),
        )
        assertEquals(PluginRejectionCode.PERMISSION_DENIED, rejection.code)
        assertTrue(rejection.message.contains("PLUGIN_STORAGE"), rejection.message)
    }

    @Test
    fun `加载期哈希不符先于权限被拒`() {
        val installed = policy.copy(
            installedHashes = mapOf("plugin.test" to "11".repeat(32)),
            approvedUnsignedHashes = mapOf("plugin.test" to "11".repeat(32)),
        )
        val rejection = rejectionOf(
            PluginValidator.verifyAtLoad(
                "plugin.test", "22".repeat(32), SigningState.Unsigned, installed,
                requestedPermissions = setOf("PLUGIN_STORAGE"),
            ),
        )
        assertEquals(PluginRejectionCode.LOAD_HASH_MISMATCH, rejection.code)
    }

    // ---- plugin dependencies -------------------------------------------------

    @Test
    fun `依赖的插件已接受则通过`() {
        val withDep = policy.copy(acceptedPluginVersions = mapOf("ev.buan.plugins.ping.api" to "1.0.0"))
        val result = PluginValidator.validate(
            candidate(dependencies = listOf("ev.buan.plugins.ping.api")), withDep,
        )
        assertTrue((result) is ValidationResult.Accepted)
    }


    @Test
    fun `an older dependency version is rejected with its own code`() {
        val candidate = candidate(dependencies = listOf("dep@2.0.0"))
        val withDep = policy.copy(acceptedPluginVersions = mapOf("dep" to "1.9.9"))

        val result = PluginValidator.validate(candidate, withDep)

        assertTrue(result is ValidationResult.Rejected)
        assertEquals(PluginRejectionCode.DEPENDENCY_VERSION, (result as ValidationResult.Rejected).rejection.code)
        assertTrue(result.rejection.message.contains("2.0.0") && result.rejection.message.contains("1.9.9"))
    }

    @Test
    fun `a satisfied dependency version passes the gate`() {
        val candidate = candidate(dependencies = listOf("dep@1.2"))
        val withDep = policy.copy(acceptedPluginVersions = mapOf("dep" to "1.2.0"))

        assertTrue(PluginValidator.validate(candidate, withDep) is ValidationResult.Accepted)
    }

    @Test
    fun `an unparsable dependency declaration is rejected instead of ignored`() {
        // 写坏的版本段不能被当成"没有版本要求"，否则一条拼错的依赖会静默通过。
        val candidate = candidate(dependencies = listOf("dep@latest"))
        val withDep = policy.copy(acceptedPluginVersions = mapOf("dep" to "1.0.0"))

        val result = PluginValidator.validate(candidate, withDep)

        assertTrue(result is ValidationResult.Rejected)
        assertEquals(PluginRejectionCode.DEPENDENCY_MISSING, (result as ValidationResult.Rejected).rejection.code)
    }

    @Test
    fun `依赖的插件不存在则拒绝并给出错误码`() {
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(dependencies = listOf("ev.buan.plugins.ping.api")), policy),
        )
        assertEquals(PluginRejectionCode.DEPENDENCY_MISSING, rejection.code)
        assertEquals(true, rejection.message.contains("ev.buan.plugins.ping.api"))
    }
}
