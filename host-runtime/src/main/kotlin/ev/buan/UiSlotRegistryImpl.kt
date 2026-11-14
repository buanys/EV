package ev.buan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import org.cordis.api.UiSlot
import org.cordis.api.UiSlotContent
import org.cordis.api.UiSlotRegistry

/**
 * 槽位注册表的实现。
 *
 * 规则与工具总线一致（见 [ev.buan.plugin.tools.ToolRegistryImpl] 的注释）：
 * 按 owner 归组、重复贡献即替换、撤销按 owner 一把清掉。
 *
 * 排序是确定性的：先比 priority 降序，再比 owner 字典序。后者只是为了"同样优先级时
 * 每次渲染顺序一样"——不确定的顺序会让截图对比与回放变得没法做。
 */
/**
 * 插件 → 宿主导航的单向通道。
 *
 * 注册表（host-runtime）不知道页面栈，页面栈在 host-app 的组合里；
 * host-app 组合时把 lambda 挂到 [handler]。放一个公开单例而不是把实现类
 * 改成 public：注册表自身的可见性不变，通道只有这一个洞。
 */
object UiSlotNav {
    @Volatile
    var handler: ((pageId: String) -> Unit)? = null
}

internal class UiSlotRegistryImpl : UiSlotRegistry {

    private val lock = Any()
    private val slots = mutableMapOf<UiSlot, MutableList<UiSlotContent>>()



    /** 组合观察到的那一个数；contribute / revoke 都在锁内加一。 */
    private val changes = mutableIntStateOf(0)
    override val version: State<Int> get() = changes

    override fun contribute(
        owner: String,
        slot: UiSlot,
        priority: Int,
        content: @Composable () -> Unit,
    ) {
        synchronized(lock) {
            val list = slots.getOrPut(slot) { mutableListOf() }
            // 同一个 owner 对同一个槽位只留一条：重新激活时重复贡献是正常路径。
            list.removeAll { it.owner == owner }
            list += UiSlotContent(owner, slot, priority, content)
            changes.intValue += 1
        }
    }

    override fun revoke(owner: String) {
        synchronized(lock) {
            val removed = slots.values.any { list -> list.removeAll { it.owner == owner } }
            if (removed) changes.intValue += 1
        }
    }

    override fun contents(slot: UiSlot): List<UiSlotContent> = synchronized(lock) {
        val sorted = slots[slot].orEmpty()
            .sortedWith(compareByDescending<UiSlotContent> { it.priority }.thenBy { it.owner })
        if (slot.replaceable) sorted.take(1) else sorted
    }

    override fun openPage(pageId: String) {
        UiSlotNav.handler?.invoke(pageId)
    }
}
