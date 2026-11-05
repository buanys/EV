package ev.buan.plugins.hello

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模板自带一个测试，不是为了覆盖率，是为了证明三件事是通的：
 * 单测能编译、能跑、而且不需要 Android 运行时。
 *
 * 写新插件时保留它，把断言换成你自己的逻辑。
 */
class HelloPluginTest {

    @Test
    fun `the service answers`() {
        assertEquals("pong", PongService().ping())
    }

    @Test
    fun `manifest id matches the sidecar id`() {
        // sidecar（build.gradle.kts 里的 JSON）和代码里的 manifest 是两份 id，
        // 对不上时宿主会按 sidecar 找入口类，然后抛 ClassNotFoundException。
        assertEquals("ev.buan.plugins.hello", HelloPlugin().manifest.id)
    }

    @Test
    fun `the plugin declares the service it publishes`() {
        val capability = HelloPlugin().manifest.capabilities.single()
        assertEquals("hello/ping", capability.id)
    }
}
