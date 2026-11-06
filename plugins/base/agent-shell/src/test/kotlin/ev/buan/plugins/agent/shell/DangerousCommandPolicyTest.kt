package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.ApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这组用例的重点不是"拦住了多少"，而是**没有多拦**：
 * 每一条 ASK 的断言都在守住"误拦比漏拦更贵"。
 */
class DangerousCommandPolicyTest {

    private fun judge(command: String, tool: String = "run_shell"): ApprovalPolicy.Verdict =
        DangerousCommandPolicy.judge(tool, """{"command":${escape(command)}}""")

    private fun escape(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `rm -rf on the root is refused`() {
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("rm -rf /"))
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("rm -rf /*"))
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("rm -rf / --no-preserve-root"))
    }

    @Test
    fun `case and spacing do not slip through`() {
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("RM  -RF   /"))
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("sudo rm -fr /"))
    }

    @Test
    fun `formatting a filesystem is refused`() {
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("mkfs.ext4 /dev/block/sda1"))
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("wipefs -a /dev/block/sda"))
    }

    @Test
    fun `writing a block device is refused`() {
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("dd if=/dev/zero of=/dev/block/sda"))
        assertEquals(ApprovalPolicy.Verdict.DENY, judge("echo x > /dev/block/sda"))
    }

    @Test
    fun `deleting inside a directory still asks a human`() {
        // 这是最容易被误拦的一条：它是正常的开发操作，必须交给人。
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("rm -rf /data/local/tmp/build"))
    }

    @Test
    fun `ordinary commands ask`() {
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("ls -la /sdcard"))
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("reboot"))
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("pm uninstall com.example"))
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("chmod -R 777 /data/local/tmp"))
    }

    @Test
    fun `other tools are left to the human`() {
        // 策略只认 run_shell；别的工具即使叫 rm_rf 也不该被它判死。
        assertEquals(ApprovalPolicy.Verdict.ASK, judge("rm -rf /", tool = "file_write"))
    }

    @Test
    fun `a broken payload falls back to asking`() {
        assertEquals(ApprovalPolicy.Verdict.ASK, DangerousCommandPolicy.judge("run_shell", "not json"))
        assertEquals(ApprovalPolicy.Verdict.ASK, DangerousCommandPolicy.judge("run_shell", "{}"))
    }
}
