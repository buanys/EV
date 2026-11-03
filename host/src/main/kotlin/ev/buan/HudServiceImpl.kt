package ev.buan

import org.cordis.api.HudService
import org.cordis.api.HudState

/**
 * [HudService] 的宿主实现。
 *
 * 和 [ScreenServiceImpl] 一样只是一层转发：真正的窗口归无障碍服务所有（只有它能用
 * `TYPE_ACCESSIBILITY_OVERLAY`），这里负责让插件看到一份稳定的契约。
 *
 * 服务没连上时不报错、不排队：提示层丢了不影响 agent 干活，只是用户少了一层可见性。
 */
internal class HudServiceImpl : HudService {

    override val available: Boolean get() = EvAccessibilityService.isConnected()

    override fun show(state: HudState) = EvAccessibilityService.showHud(state)

    override fun hide() = EvAccessibilityService.hideHud()
}
