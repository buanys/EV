package ev.buan.hostcore.trust

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "能不能进子进程"是装配阶段就能回答的问题，所以它是纯函数。
 *
 * 这些用例守的是一条真机换来的线：**不声明就不隔离**。第一版按权限推断，17 个插件里
 * 12 个被塞进子进程、全部因为拿不到宿主服务而失败（它们在运行时用 `ServiceDirectory`
 * 查服务，权限声明里看不出来）。
 */
class RestrictedProfileTest {

    @Test
    fun `没有声明就不隔离`() {
        val reason = RestrictedProfile.unsupportedReason(
            declared = false,
            permissions = emptySet(),
            shortcutCount = 0,
            dependencies = emptyList(),
        )
        assertNotNull(reason)
        assertTrue(reason.contains("isolation"))
    }

    @Test
    fun `声明了且只要存储与网络就可以隔离`() {
        assertTrue(
            RestrictedProfile.isolatable(
                declared = true,
                permissions = setOf("NETWORK", "PLUGIN_STORAGE"),
                shortcutCount = 0,
                dependencies = emptyList(),
            ),
        )
        assertNull(
            RestrictedProfile.unsupportedReason(
                declared = true,
                permissions = emptySet(),
                shortcutCount = 0,
                dependencies = emptyList(),
            ),
        )
    }

    @Test
    fun `声明了但还要别的宿主服务就不行`() {
        val reason = RestrictedProfile.unsupportedReason(
            declared = true,
            permissions = setOf("PLUGIN_STORAGE", "SECRETS"),
            shortcutCount = 0,
            dependencies = emptyList(),
        )
        assertNotNull(reason)
        assertTrue(reason.contains("SECRETS"))
    }

    @Test
    fun `声明了但带桌面入口就不行`() {
        assertFalse(
            RestrictedProfile.isolatable(
                declared = true,
                permissions = emptySet(),
                shortcutCount = 1,
                dependencies = emptyList(),
            ),
        )
    }

    @Test
    fun `声明了但带插件依赖就不行`() {
        val reason = RestrictedProfile.unsupportedReason(
            declared = true,
            permissions = emptySet(),
            shortcutCount = 0,
            dependencies = listOf("agent.tools@1.1.0"),
        )
        assertNotNull(reason)
        assertTrue(reason.contains("依赖"))
    }
}
