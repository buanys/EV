package ev.buan.hostcore.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 安装结论的判定表。
 *
 * 这段逻辑之所以值得单独测：在真机上，"被拒"和"等人批"和"压根没扫到"三种情况的**外部表现完全一样**
 * ——插件都没生效、日志里都没有线索。把判定抽到这里，至少规则本身是可枚举的。
 */
class InstallDecisionTest {

    private fun decide(
        id: String? = "taotie.smoke",
        entry: String? = "taotie.smoke.SmokePlugin",
        accepted: Set<String> = emptySet(),
        pending: Map<String, String> = emptyMap(),
        errors: List<Pair<String, String>> = emptyList(),
        active: Boolean = false,
    ) = InstallDecision.decide(id, entry, accepted, pending, errors, active)

    @Test
    fun `sidecar 缺 id 时是元数据问题`() {
        val outcome = decide(id = null)
        assertEquals(InstallStatus.REJECTED, outcome.status)
        assertEquals(InstallDecision.CODE_METADATA, outcome.code)
    }

    @Test
    fun `非法 id 提前拒绝`() {
        val outcome = decide(id = "../escape")
        assertEquals(InstallStatus.REJECTED, outcome.status)
        assertEquals(InstallDecision.CODE_METADATA, outcome.code)
    }

    @Test
    fun `缺入口类提前拒绝`() {
        val outcome = decide(entry = null)
        assertEquals(InstallStatus.REJECTED, outcome.status)
        assertEquals(InstallDecision.CODE_METADATA, outcome.code)
    }

    @Test
    fun `扫描接受就是装上了并带回激活状态`() {
        val outcome = decide(accepted = setOf("taotie.smoke"), active = true)
        assertEquals(InstallStatus.INSTALLED, outcome.status)
        assertEquals(true, outcome.active)
    }

    @Test
    fun `待批准带的是哈希而不是 id`() {
        val outcome = decide(pending = mapOf("taotie.smoke" to "abc123"))
        assertEquals(InstallStatus.NEEDS_APPROVAL, outcome.status)
        assertEquals("abc123", outcome.sha256)
    }

    @Test
    fun `拒绝优先于待批准`() {
        // 同一份容器既被拒又在待批准表里时不可能是"等人批"：事实是它被拒了。
        val outcome = decide(
            pending = mapOf("taotie.smoke" to "abc123"),
            errors = listOf("DUPLICATE_PLATFORM_CLASS" to "taotie.smoke 定义了宿主已有的类"),
        )
        assertEquals(InstallStatus.REJECTED, outcome.status)
        assertEquals("DUPLICATE_PLATFORM_CLASS", outcome.code)
    }

    @Test
    fun `别人的拒绝不算这颗插件的`() {
        val outcome = decide(
            accepted = setOf("taotie.smoke"),
            errors = listOf("DEPENDENCY_MISSING" to "另一个插件缺依赖"),
        )
        assertEquals(InstallStatus.INSTALLED, outcome.status)
        assertNull(outcome.code)
    }

    @Test
    fun `扫描没提这个 id 时如实说没看见`() {
        val outcome = decide()
        assertEquals(InstallStatus.REJECTED, outcome.status)
        assertEquals(InstallDecision.CODE_UNSEEN, outcome.code)
    }
}
