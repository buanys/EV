package ev.buan.plugins.agent.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 浏览器的标签集合：**面板关掉之后它还活着**。
 *
 * 之前 `BrowserPage` 里一句 `remember { BrowserSession() }` 就把整件事锁死了：
 * 组合一离开，WebView 被销毁，页面、JS 上下文、滚动位置全部没了——用户在应用里
 * 切到别的页面再回来，等于重开了浏览器。这个类存在的唯一理由就是把这层状态
 * 提到插件实例上（见 [AgentBrowsePlugin.browser]），让"离开页面"只等于"放下视图"。
 *
 * 三条规矩：
 *
 *  1. **关掉最后一个标签不算关闭**：浏览器总得有一页可看，否则回来是一块空白。
 *  2. **标签数有上限**：每个标签是一个 WebView，一个 WebView 是几 MB；
 *     允许无限开等于让用户替我们做内存压测（见 [MAX_TABS]）。
 *  3. **销毁只发生在两处**：用户关掉这个标签、插件停用。其余时候只 detach。
 */
internal class BrowserTabs {

    /** 标签页的标识：自增，不复用（复用会让"上一次的 id"意外指向新标签）。 */
    private var nextId = 1L

    private val open = mutableStateListOf<BrowserSession>()

    /** 当前显示的那个标签的 id；0 表示还没有任何标签。 */
    var activeId by mutableStateOf(0L)
        private set

    /** 所有标签，按打开顺序。 */
    val tabs: List<BrowserSession> get() = open

    /** 当前标签；没有标签时为 null。 */
    val active: BrowserSession? get() = open.firstOrNull { it.id == activeId }

    /** 开一个新标签并切过去；到上限时返回 null（调用方负责说一句人话）。 */
    fun openTab(): BrowserSession? {
        if (open.size >= MAX_TABS) return null
        val tab = BrowserSession(nextId++)
        open += tab
        activeId = tab.id
        return tab
    }

    fun activate(id: Long) {
        if (open.any { it.id == id }) activeId = id
    }

    /**
     * 关掉一个标签；返回关掉之后应该显示哪个标签的 id。
     *
     * 关掉当前标签时，优先选它右边的那个——这与桌面浏览器的习惯一致，
     * 也是"关掉当前页之后你还在原地"的唯一解释。
     */
    fun close(id: Long): Long {
        val index = open.indexOfFirst { it.id == id }
        if (index < 0) return activeId
        val closing = open[index]
        open.removeAt(index)
        closing.dispose()
        if (activeId == id) {
            activeId = open.getOrNull(index)?.id ?: open.lastOrNull()?.id ?: 0L
        }
        return activeId
    }

    /** 插件停用/卸载时调用：销毁所有标签的 WebView。 */
    fun dispose() {
        open.forEach { it.dispose() }
        open.clear()
        activeId = 0L
    }

    private companion object {
        /** 五个标签 ≈ 五份 WebView 内存；再多就该让用户先关几个了。 */
        const val MAX_TABS = 5
    }
}
