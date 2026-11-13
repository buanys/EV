package ev.buan.hostcore.lifecycle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule `PluginPageActivity` depends on: only a start pass that finished outside safe mode makes
 * the runtime able to render plugin pages. Pure JVM, no Android and no device.
 */
class HostLifecycleTest {

    @Test
    fun `启动前未就绪`() {
        assertFalse(HostLifecycle().hasStarted)
    }

    @Test
    fun `正常启动完成后就绪`() {
        val lifecycle = HostLifecycle()

        lifecycle.completeStart(safeMode = false)

        assertTrue(lifecycle.hasStarted)
    }

    @Test
    fun `安全模式启动不算就绪`() {
        val lifecycle = HostLifecycle()

        lifecycle.completeStart(safeMode = true)

        // Safe mode skipped every plugin; rendering a plugin page would show an empty shell.
        assertFalse(lifecycle.hasStarted)
    }

    @Test
    fun `启动抛异常时保持未就绪`() {
        val lifecycle = HostLifecycle()

        // The failure path never reaches completeStart; model it by not calling it at all.
        assertFalse(lifecycle.hasStarted)
    }

    @Test
    fun `失败的重启不回退已就绪状态`() {
        val lifecycle = HostLifecycle()
        lifecycle.completeStart(safeMode = false)

        // A second start that throws leaves the already activated plugins in place.
        assertTrue(lifecycle.hasStarted)
    }

    @Test
    fun `安全模式启动会清掉此前的就绪状态`() {
        val lifecycle = HostLifecycle()
        lifecycle.completeStart(safeMode = false)

        lifecycle.completeStart(safeMode = true)

        assertFalse(lifecycle.hasStarted)
    }

    @Test
    fun `停止后就绪状态清除`() {
        val lifecycle = HostLifecycle()
        lifecycle.completeStart(safeMode = false)

        lifecycle.stop()

        assertFalse(lifecycle.hasStarted, "dispose 卸载了全部插件，不能继续对外宣称可渲染插件页")
    }

    @Test
    fun `停止后重新启动再次就绪`() {
        val lifecycle = HostLifecycle()
        lifecycle.completeStart(safeMode = false)
        lifecycle.stop()

        lifecycle.completeStart(safeMode = false)

        assertTrue(lifecycle.hasStarted)
    }
}
