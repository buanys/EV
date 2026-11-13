package ev.buan.hostcore.validation

import ev.buan.hostcore.trust.PluginOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 契约版本门的两条路径。
 *
 * 这一组测试守的是一个具体的失败模式：**两个门同时生效**。那时插件作者无法从报错里
 * 判断"是整数版本不对还是范围不对"，只能读源码。所以每个用例都断言了具体是哪条路径拒的。
 */
class PluginValidatorApiGateTest {

    private val hostCert = "aa".repeat(32)

    private val policy = HostPolicy(
        hostCertificateSha256 = setOf(hostCert),
        supportedApiVersion = 1,
        hostApiVersion = HostApiVersion(1, 0, 0),
    )

    private fun candidate(
        apiVersion: Int = 1,
        hostApiRange: String? = null,
    ) = PluginCandidate(
        id = "plugin.test",
        version = "1.0.0",
        entryClass = "test.Plugin",
        apiVersion = apiVersion,
        hostApiRange = hostApiRange,
        sha256 = "11".repeat(32),
        origin = PluginOrigin.EXTERNAL,
        signing = SigningState.Signed(setOf(hostCert)),
    )

    private fun rejectionOf(result: ValidationResult): PluginRejection =
        assertIs<ValidationResult.Rejected>(result).rejection

    @Test
    fun `声明了包含宿主的范围就通过`() {
        val result = PluginValidator.validate(candidate(hostApiRange = ">=1.0.0 <2.0.0"), policy)
        assertIs<ValidationResult.Accepted>(result)
    }

    @Test
    fun `范围不含宿主时拒绝，理由里带上宿主版本与范围`() {
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(hostApiRange = ">=2.0.0 <3.0.0"), policy),
        )
        assertEquals(PluginRejectionCode.API_VERSION_MISMATCH, rejection.code)
        assertEquals(">=2.0.0 <3.0.0", rejection.detail)
        val message = rejection.message
        require("1.0.0" in message) { "理由里要出现宿主版本：$message" }
        require(">=2.0.0 <3.0.0" in message) { "理由里要回显范围：$message" }
    }

    @Test
    fun `范围解析不了是独立错误码，不会被当成版本不兼容`() {
        val rejection = rejectionOf(
            PluginValidator.validate(candidate(hostApiRange = "^1.0.0"), policy),
        )
        assertEquals(PluginRejectionCode.API_RANGE_INVALID, rejection.code)
    }

    @Test
    fun `非法范围不会被静默放行`() {
        val rejection = rejectionOf(PluginValidator.validate(candidate(hostApiRange = "*"), policy))
        assertEquals(PluginRejectionCode.API_RANGE_INVALID, rejection.code)
    }

    @Test
    fun `声明了范围就不再走整数门`() {
        // apiVersion 故意填 0（整数门会拒），但范围命中：应当通过。
        val result = PluginValidator.validate(
            candidate(apiVersion = 0, hostApiRange = ">=1.0.0 <2.0.0"),
            policy,
        )
        assertIs<ValidationResult.Accepted>(result)
    }

    @Test
    fun `没声明范围的旧容器仍然走整数门`() {
        assertIs<ValidationResult.Accepted>(PluginValidator.validate(candidate(apiVersion = 1), policy))

        val rejection = rejectionOf(PluginValidator.validate(candidate(apiVersion = 2), policy))
        assertEquals(PluginRejectionCode.API_VERSION_MISMATCH, rejection.code)
        require("整数等值门" in rejection.message) {
            "旧路径的报错要说清走的是哪条门：${rejection.message}"
        }
    }

    @Test
    fun `空白范围按没声明处理，不报解析失败`() {
        val result = PluginValidator.validate(candidate(hostApiRange = "   "), policy)
        assertIs<ValidationResult.Accepted>(result)
    }
}
