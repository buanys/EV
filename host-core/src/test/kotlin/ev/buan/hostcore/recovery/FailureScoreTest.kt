package ev.buan.hostcore.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 失败计分的三条规则，每条都钉一个反例。
 *
 * 这道门存在的理由：一个必然失败的插件以前每次启动都要付一次 90 秒超时，
 * 而用户看到的只是"又失败了"。计分把它变成"它已经被停用，去重置或卸载"。
 */
class FailureScoreTest {

    @Test
    fun `three failures block further attempts`() {
        var score = FailureScore()
        repeat(FailureScore.LIMIT - 1) {
            score = score.afterFailure()
            assertFalse(score.blocked, "第 ${it + 1} 次失败还不该封顶")
        }
        score = score.afterFailure()
        assertTrue(score.blocked)
        assertEquals(3, score.count)
    }

    @Test
    fun `a success lowers the count by one instead of clearing it`() {
        // 清零会让"每两次成功崩一次"的插件永远运行下去；减一才反映真实的稳定性。
        val score = FailureScore(2).afterSuccess()
        assertEquals(1, score.count)
        assertFalse(score.blocked)
    }

    @Test
    fun `a success never goes below zero`() {
        assertEquals(0, FailureScore().afterSuccess().count)
        assertEquals(0, FailureScore(0).afterSuccess().afterSuccess().count)
    }

    @Test
    fun `once blocked a success does not unblock it by itself`() {
        // 解除必须是显式的：自动恢复等于没有这道门。
        val blocked = FailureScore(FailureScore.LIMIT)
        assertTrue(blocked.blocked)
        assertEquals(FailureScore.LIMIT - 1, blocked.afterSuccess().count)
        assertFalse(blocked.afterSuccess().blocked, "减一后确实低于阈值")
    }

    @Test
    fun `the count stops climbing at the ceiling`() {
        // 界面不该显示"失败 47 次"——那个数字没有信息量，只会让人以为还有别的问题。
        var score = FailureScore()
        repeat(50) { score = score.afterFailure() }
        assertEquals(FailureScore.CEILING, score.count)
        assertTrue(score.blocked)
    }
}
