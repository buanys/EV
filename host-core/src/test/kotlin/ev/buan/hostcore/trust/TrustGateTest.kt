package ev.buan.hostcore.trust

import ev.buan.hostcore.validation.SigningState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 信任判定是纯函数，所以这里只喂事实、只断言档位。
 *
 * 每条用例对应规则 §二 的一句话，命名也照抄那句：以后规则改了，先看哪条用例红了。
 */
class TrustGateTest {

    private val hostCert = "aa".repeat(32)
    private val hash = "11".repeat(32)

    private fun decide(
        origin: PluginOrigin = PluginOrigin.EXTERNAL,
        signing: SigningState = SigningState.Unsigned,
        sha256: String = hash,
        approved: String? = null,
    ) = TrustGate.decide(
        origin = origin,
        signing = signing,
        sha256 = sha256,
        approvedSha256 = approved,
        hostCertificateSha256 = setOf(hostCert),
    )

    @Test
    fun `随包内置即可信，不看容器自己的签名`() {
        // jar 装不下 Android 签名，而 assets 里的东西改一个字节 APK 签名就已失效，
        // 所以内置容器不需要第二份签名。
        val builtIn = decide(origin = PluginOrigin.BUILT_IN)
        assertEquals(TrustTier.TRUSTED, builtIn.tier)
        val builtInForeign = decide(origin = PluginOrigin.BUILT_IN, signing = SigningState.Signed(setOf("bb")))
        assertEquals(TrustTier.TRUSTED, builtInForeign.tier)
    }

    @Test
    fun `外部导入且签名与宿主一致即可信`() {
        val decision = decide(signing = SigningState.Signed(setOf(hostCert)))
        assertEquals(TrustTier.TRUSTED, decision.tier)
        assertTrue(decision.mayShareProcess)
    }

    @Test
    fun `未签名且未批准是不可信`() {
        val decision = decide()
        assertEquals(TrustTier.UNTRUSTED, decision.tier)
        assertFalse(decision.mayShareProcess)
    }

    @Test
    fun `未签名但批准过这批字节是受限`() {
        val decision = decide(approved = hash)
        assertEquals(TrustTier.RESTRICTED, decision.tier)
        // 受限能跑，但不是同进程：这个区别正是这一档存在的理由。
        assertTrue(decision.mayShareProcess)
    }

    @Test
    fun `批准的是字节，换了字节就不再受限而是不可信`() {
        val decision = decide(sha256 = "22".repeat(32), approved = hash)
        assertEquals(TrustTier.UNTRUSTED, decision.tier)
    }

    @Test
    fun `第三方签名默认拒绝`() {
        val decision = decide(signing = SigningState.Signed(setOf("bb".repeat(32))))
        assertEquals(TrustTier.UNTRUSTED, decision.tier)
        assertTrue(decision.reason.contains("第三方"))
    }

    @Test
    fun `空证书集是签坏了，批准也不放行`() {
        val decision = decide(signing = SigningState.Signed(emptySet()), approved = hash)
        assertEquals(TrustTier.UNTRUSTED, decision.tier)
    }

    @Test
    fun `判定缺失按不可信处理`() {
        // 没有批准记录、没有签名、来源是外部：任一事实缺失都落在这里。
        assertEquals(TrustTier.UNTRUSTED, decide(approved = null).tier)
    }

    @Test
    fun `三档都有能念给人听的话`() {
        TrustTier.entries.forEach { tier ->
            assertTrue(TrustGate.label(tier).isNotBlank())
            assertTrue(TrustGate.meaning(tier).isNotBlank())
        }
    }
}
