package org.cordis.api

import androidx.compose.runtime.Composable
import org.cordis.ServiceKey

/**
 * 宿主界面里预留的孔。
 *
 * 默认界面在宿主里（`host-app`），但这些位置允许插件填。槽位**少且具名**是刻意的：
 * 一旦允许"任意插槽"，插件的代码就会开始依赖宿主的布局细节，接口就宽了。
 *
 * [replaceable] 决定同一个槽位能站几个：
 *
 * - `true`：只有一个赢家（优先级最高的那个）。首页主视觉与全局浮层属于这类——
 *   两个插件同时往首页顶部塞东西，会互相盖住。
 * - `false`：按优先级累加，全都能显示。菜单项、设置分区、消息操作属于这类。
 */
enum class UiSlot(val replaceable: Boolean) {
    /** 首页顶部的主视觉。默认空。 */
    HomeHero(true),

    /** 输入框上方的快捷动作。 */
    HomeAboveComposer(false),

    /** 每条助手消息下方的操作（复制、重试、分支）。 */
    MessageActions(false),

    /** 设置页里追加的分区。 */
    SettingsSections(false),

    /** 侧面页里的入口。 */
    DrawerItems(false),

    /** 浮在一切之上的层（悬浮窗、指示器）。只有一个赢家。 */
    GlobalOverlay(true),
}

/**
 * 一个槽位里的内容。
 *
 * [content] 是一个 composable 函数，不是已渲染的节点——宿主决定它何时、在哪里被组合。
 * 这也意味着：**它会在宿主的进程与组合里执行**，抛出的异常宿主接不住（Compose 没有
 * error boundary）。坏插件会被安全模式挡在下一次启动之外，但这一次会崩。
 */
data class UiSlotContent(
    /** 贡献者的插件 id。同一个 id 再次贡献视为替换，不是追加。 */
    val owner: String,
    val slot: UiSlot,
    val priority: Int,
    val content: @Composable () -> Unit,
)

/**
 * 槽位注册表：插件往宿主界面的具名位置里放东西。
 *
 * 与 [ToolRegistry] 同构：一个总线、按 owner 归组、重新贡献即替换、停用时撤销。
 * 宿主是唯一的渲染方，插件不参与布局。
 */
interface UiSlotRegistry {

    /**
     * 往 [slot] 里放一段界面。同一个 [owner] 对同一个槽位的重复调用是**替换**。
     *
     * @param priority 越大越靠前；[UiSlot.replaceable] 时只有最大的那个会被渲染。
     */
    fun contribute(owner: String, slot: UiSlot, priority: Int = 0, content: @Composable () -> Unit)

    /** 撤掉 [owner] 贡献的全部内容。插件停用/卸载时调用。 */
    fun revoke(owner: String)

    /** 某个槽位当前的内容，已按优先级排序；可替换槽位只返回赢家。 */
    fun contents(slot: UiSlot): List<UiSlotContent>

    companion object {
        val Key = ServiceKey<UiSlotRegistry>("host/ui-slots")
    }
}
