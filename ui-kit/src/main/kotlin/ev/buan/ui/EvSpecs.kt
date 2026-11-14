package ev.buan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 框架认得的一组图标名。
 *
 * 只列**页面会用到的语义**，不是图标库的目录：`More` 就是"更多"，换成三个点还是三条横线
 * 是图标集的事。这样主题换一套图标，页面一行都不用动。
 *
 * 这个枚举刻意短。每加一个名字，都得回答"为什么现有的不够用"——否则它会慢慢长成
 * 一个和 CouiIcons 平行的目录，两边各维护一份。
 */
enum class EvIcon {
    Back,
    Close,
    Add,
    More,
    Search,
    Setting,
    Mic,
    Bell,
    Send,
    Apps,
    NewChat,
    Shield,
    Folder,
    /** Agent 运行时：和「插件管理」必须用不同的图标，否则抽屉里两行长得一样。 */
    Agent,
    // 下面几个是权限中心需要的。COUI 的 Filled 里有对应图形，直接映射；
    // 没有对应图形的（短信、日历）在调用侧兜底，不自己画一套——
    // 自绘图标和 COUI 的笔画粗细对不上，一列五个里就会露出两个体系。
    Camera,
    Location,
    Contacts,
    Overlay,
    Usage,
}

/**
 * 主题的图标语言。返回 null 表示"用框架默认的那一个"。
 *
 * 与旧实现的区别：旧版把图标集绑在一个会自绘状态图形的系统上（`EvGlyphKind`），
 * 于是"换个图标"要连带理解那套图形。这里只留一步查表，够用且不用解释。
 */
fun interface EvIconSet {
    fun icon(name: EvIcon): ImageVector?

    companion object {
        /** 一个都不覆盖：全走默认。 */
        val Default: EvIconSet = EvIconSet { null }
    }
}

/**
 * 层次感，用三个透明度表达。
 *
 * 这个 App 不用阴影——深色主题里的阴影就是一块更暗的矩形，看不出层次。层次靠两件事：
 * 面结束处的发丝线，以及浮在页面之上的东西顶上的一道微亮。
 */
data class EvElevationSpec(
    /** 直接贴在页面上的卡片边框。 */
    val cardBorderAlpha: Float = 0.05f,
    /** 浮起来的面（抽屉、菜单、模型卡）的边框。 */
    val floatBorderAlpha: Float = 0.10f,
    /** 浮面顶部的微亮，让它看起来在页面之上。 */
    val floatHighlightAlpha: Float = 0.06f,
) {
    companion object {
        val Default: EvElevationSpec = EvElevationSpec()
    }
}

/**
 * 当前主题的颜色，供框架组件与宿主界面使用。
 *
 * `LocalEvColorTokens` 是 internal 的：composition local 是实现细节，
 * 让调用方直接读它，等于把"颜色存在一个 CompositionLocal 里"变成契约。
 */
val EvColors: EvColorTokens
    @Composable get() = LocalEvColorTokens.current
