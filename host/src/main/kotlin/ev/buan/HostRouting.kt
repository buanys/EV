package ev.buan

import android.content.SharedPreferences
import org.cordis.Plugin
import org.cordis.api.AgentPage
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import org.cordis.loader.AndroidModuleLoader

/**
 * 页面归属：首页是谁、某个插件的页面/UI/标题从哪来。
 *
 * 这是宿主里唯一"知道插件叫什么、页面长什么样"的地方，所以它只读：
 * 页面归属发生变化必须经 Ops → 重扫，不能在这里改（见 ADR-0002 不变量 2）。
 *
 * 依赖用函数传进来而不是直接拿 `HostComposition`：路由只需要四个问题
 * （有哪些插件、启没启用、有没有失败、被展示时通知谁），不该握住整个宿主。
 */
internal class HostRouting(
    private val modules: AndroidModuleLoader,
    private val preferences: SharedPreferences,
    private val pluginIds: () -> Collection<String>,
    private val isEnabled: (String) -> Boolean,
    private val failureOf: (String) -> String?,
    private val onShown: (String) -> Unit,
) {

    /**
     * 启动页。优先用户选的，其次默认插件，最后任意一个没声明退出的页面。
     *
     * Keeping this in the host means a broken home plugin degrades to the fallback screen
     * instead of leaving the user with no way into management.
     */
    fun homePage(): AgentPage? {
        val preferred = preferences.getString(KEY_HOME, null)
        if (preferred != null) {
            pageFor(preferred)?.let { return it }
        }
        pageFor(DEFAULT_HOME_PLUGIN)?.let { return it }
        // Fallback: any page that did not opt out. Without the filter, a memory or tools page would
        // silently become the launch screen whenever ev-ui is disabled or failed to load.
        return pluginIds().firstNotNullOfOrNull { id ->
            pageFor(id)?.takeIf { it.isHomeCandidate }
        }
    }

    fun setHomePage(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_HOME) else putString(KEY_HOME, id)
        }.apply()
    }

    /**
     * Plugin that provides a full page, or null when it only renders inline content.
     *
     * A non-null answer means the caller is about to render this plugin, so it also records
     * the shown plugin id for crash attribution.
     */
    fun pageFor(id: String): AgentPage? {
        if (!isEnabled(id) || failureOf(id) != null) return null
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull()
        return (instance as? AgentPage)?.also { onShown(id) }
    }

    /**
     * Live UI of one plugin, or null when it is disabled, failed or has no UI.
     *
     * Also records the shown plugin id; see [pageFor].
     */
    fun uiFor(id: String): AgentUi? {
        if (!isEnabled(id) || failureOf(id) != null) return null
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*>
        return (instance as? AgentUi)?.also { onShown(id) }
    }

    fun titleFor(id: String): String {
        val instance = runCatching { modules.peek(modules.moduleUrl(id)) }.getOrNull() as? Plugin<*>
        return (instance as? AgentSurface)?.title ?: id
    }

    companion object {
        const val KEY_HOME = "home.page"
        const val DEFAULT_HOME_PLUGIN = "ev.buan.plugins.ev.ui"
    }
}
