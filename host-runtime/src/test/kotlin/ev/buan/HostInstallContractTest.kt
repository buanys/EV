package ev.buan

import ev.buan.hostcore.install.InstallOutcome
import ev.buan.hostcore.install.InstallStatus
import org.cordis.api.PluginInstallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装结论的形状映射。
 *
 * 这里只钉一件事：**host-core 说装成了，宿主就必须把"现在跑着"如实带出去**。
 * 卡片上那句"已启用"来自 `active`，它要是恒为 false，一次装完的用户会以为没装上——
 * 这正是 ADR-0004 里"装完还要再启动一次才算数"那段旧文案的由来，后来被证明是多余的。
 *
 * 两个 enum 刻意分开（host-core 不该认识契约），所以映射只发生在 `toContract` 一处，
 * 也就只有这一处需要钉。
 */
class HostInstallContractTest {

    private fun outcome(
        status: InstallStatus,
        active: Boolean = false,
        code: String? = null,
        sha: String? = null,
        message: String = "",
    ) = InstallOutcome(
        status = status,
        pluginId = "ev.buan.plugins.demo",
        code = code,
        sha256 = sha,
        message = message,
        active = active,
    )

    @Test
    fun `installed maps to installed and carries the live flag`() {
        val contract = outcome(InstallStatus.INSTALLED, active = true).toContract(version = "1.2.3")
        assertEquals(PluginInstallStatus.INSTALLED, contract.status)
        assertTrue(contract.active)
        assertEquals("1.2.3", contract.version)
    }

    @Test
    fun `needs approval maps to needs approval and is never active`() {
        val contract = outcome(InstallStatus.NEEDS_APPROVAL, sha = "abc").toContract(version = null)
        assertEquals(PluginInstallStatus.NEEDS_APPROVAL, contract.status)
        assertFalse(contract.active)
        assertEquals("abc", contract.sha256)
    }

    @Test
    fun `rejected keeps the code so the caller can act on it`() {
        val contract = outcome(
            InstallStatus.REJECTED,
            code = "MODS_UNWRITABLE",
            message = "无法写入 EV/mods",
        ).toContract(version = null)
        assertEquals(PluginInstallStatus.REJECTED, contract.status)
        assertEquals("MODS_UNWRITABLE", contract.code)
        assertFalse(contract.active)
    }
}
