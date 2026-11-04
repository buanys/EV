package ev.buan

import org.cordis.api.HudRenderer
import org.cordis.api.HudService
import org.cordis.api.HudState
import org.cordis.api.ServiceDirectory

/**
 * [HudService] 的宿主实现。
 *
 * 只做两件事：查当前有没有视觉实现（[HudRenderer]，由 agent-gui 发布），然后把状态转给
 * 无障碍服务去挂窗口。查不到就什么都不显示——提示层缺失不影响 agent 干活，只是少一层可见性。
 *
 * 用 lambda 拿 directory 而不是构造时持有：插件可能在宿主启动之后才装进来，缓存一个 null
 * 会让"后来装上的 agent-gui"永远没机会接管视觉。
 */
internal class HudServiceImpl(
    private val directory: () -> ServiceDirectory?,
) : HudService {

    override val available: Boolean get() = EvAccessibilityService.isConnected()

    override fun show(state: HudState) {
        val renderer: HudRenderer? = runCatching {
            directory()?.lookup(HudRenderer.Key)
        }.getOrNull()
        EvAccessibilityService.showHud(state, renderer)
    }

    override fun hide() = EvAccessibilityService.hideHud()
}
