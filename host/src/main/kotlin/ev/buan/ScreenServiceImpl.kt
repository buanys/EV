package ev.buan

import android.content.Context as AndroidContext
import org.cordis.api.ScreenAction
import org.cordis.api.ScreenDump
import org.cordis.api.ScreenService

/**
 * 屏幕服务的宿主实现。
 *
 * 作用只有一个：把 `openSettings()` 这类需要 `Context` 的部分接上，
 * 其余全部转给 [EvAccessibilityService] 的 delegate——真正的无障碍逻辑在那一边。
 */
internal class ScreenServiceImpl(private val context: AndroidContext) : ScreenService {

    private val delegate = EvAccessibilityService.delegate()

    override val available: Boolean get() = EvAccessibilityService.isConnected()

    override fun openSettings(): Boolean = EvAccessibilityService.openSettings(context)

    override fun dump(maxNodes: Int): ScreenDump = delegate.dump(maxNodes)

    override fun tap(x: Int, y: Int): ScreenAction = delegate.tap(x, y)

    override fun tapText(text: String): ScreenAction = delegate.tapText(text)

    override fun input(text: String): ScreenAction = delegate.input(text)

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ScreenAction =
        delegate.swipe(x1, y1, x2, y2, durationMs)

    override fun key(key: String): ScreenAction = delegate.key(key)

    override fun currentPackage(): String? = EvAccessibilityService.currentPackage()
}
