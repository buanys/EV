package ev.buan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cordis.api.UiSlot
import org.cordis.api.UiSlotRegistry

/**
 * Host-side handle on the slot registry, for [SlotHost].
 *
 * Deliberately a process-wide singleton rather than a composition local: the
 * registry is published once by the runtime and never replaced, and screens
 * that render a slot have no other reason to know the runtime exists. Compose
 * still recomposes correctly - the thing it observes is [UiSlotRegistry.version],
 * which lives inside the registry, not this handle.
 */
object HostSlots {
    /**
     * 每次都现取，而不是在 `onCreate` 里存一份：`runtime.slotRegistry()` 要等
     * 宿主 compose 起来、发布完服务之后才有值，早读一次拿到的是 null，
     * 而 null 会让整个槽位永远不渲染（看起来就跟"没接"一样）。
     */
    @Volatile
    var provider: (() -> UiSlotRegistry?)? = null
}

/**
 * The host's side of [UiSlotRegistry]: it renders what plugins put into a slot.
 *
 * Plugins own the content, the host owns the place and the palette. A slot host
 * is deliberately dumb - no animation, no container material, no padding that
 * fights the built-in layout; it stacks what it is given, and the plugin is
 * expected to use `ev.buan.ui` components so the result sits in the same theme.
 */
@Composable
fun SlotHost(slot: UiSlot, modifier: Modifier = Modifier) {
    val registry = HostSlots.provider?.invoke() ?: return
    // Reading the version is what subscribes this composition to the registry;
    // without it a plugin registering later would never be drawn.
    val version = registry.version.value
    if (version < 0) return
    val contents = registry.contents(slot)
    if (contents.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        contents.forEach { content -> content.content() }
    }
}
