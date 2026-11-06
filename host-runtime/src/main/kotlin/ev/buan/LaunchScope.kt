package ev.buan

/**
 * 这一次进程启动要激活哪些插件。
 *
 * 入口即意图：
 * - 用户点 EV 主图标 → 他要的是整个应用，[Full]
 * - 用户点某个快捷方式 → 他只要那一个功能，[Closure]
 *
 * 扫描和校验永远是全量的（安全），省下来的是激活：`DexClassLoader` 与 `apply()`
 * 只对闭包内的插件执行，内存与启动时间都少一截。
 */
sealed interface LaunchScope {

    /** 全部已启用的插件。 */
    data object Full : LaunchScope

    /**
     * 只激活 [root] 及其依赖闭包。
     *
     * 闭包小的插件才适合做快捷方式：`agent.browse` 只需要 api + tools，
     * 而 `agent.brain` 的闭包几乎是全部插件，给它做快捷方式等于没有省。
     */
    data class Closure(val root: String) : LaunchScope
}
