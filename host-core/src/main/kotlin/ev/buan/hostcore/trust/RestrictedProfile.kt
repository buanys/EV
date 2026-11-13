package ev.buan.hostcore.trust

/**
 * 一个受限插件能不能被扔进子进程。
 *
 * 子进程里的插件拿不到宿主进程的任何服务实例，只有一个本地的 `ToolRegistry`、一个
 * `PermissionService` 和一个（被判过权的）`PluginStorage`。所以"能不能隔离"不是策略选择，
 * 而是**这个插件要的东西子进程里有没有**：
 *
 *  - 声明了其它宿主权限 → 没有对应服务，`inject` 会一直等不到提供者，插件永远是 PENDING；
 *  - 声明了桌面入口 → 快捷方式由宿主画，子进程画不了；
 *  - 有插件依赖 → 加载器要按依赖链装一串模块，子进程只装一个。
 *
 * 三条都是**从 sidecar 元数据就能判定**的，不需要先把插件跑起来——所以这个函数是纯的，
 * 装配阶段就能回答"它进哪个执行环境"。判定为不可隔离时，受限档在正式包里不会执行
 * （见 ADR-0009）。
 *
 * 加上"必须有声明"这一条，是因为宿主看不出插件经由 `ServiceDirectory` 查了哪些服务：
 * 只按权限推，`agent-brain`、`ev-ui` 这些都会显得"可隔离"，然后在子进程里因为拿不到
 * 宿主服务而集体失败（真机上试过，见 ADR-0009 的修订记录）。
 */
object RestrictedProfile {

    /** 子进程里**有**实现的权限。`NETWORK` 只是声明与审计，不借宿主任何东西。 */
    private val SUPPORTED_PERMISSIONS = setOf("NETWORK", "PLUGIN_STORAGE")

    /**
     * @param permissions 容器声明的权限线名（`HostPermission.name`）。
     * @param shortcutCount 声明的桌面入口数量。
     * @param dependencies 声明的插件依赖（可能带 `@版本`）。
     */
    fun isolatable(
        declared: Boolean,
        permissions: Set<String>,
        shortcutCount: Int,
        dependencies: List<String>,
    ): Boolean = unsupportedReason(declared, permissions, shortcutCount, dependencies) == null

    /**
     * 为什么不能隔离；null 表示可以。
     *
     * 理由要能直接写进卡片：用户看到"受限插件不执行"时必须知道是它自己越界了，
     * 还是宿主的能力还不够——这两件事的下一步完全不同。
     */
    fun unsupportedReason(
        declared: Boolean,
        permissions: Set<String>,
        shortcutCount: Int,
        dependencies: List<String>,
    ): String? {
        if (!declared) {
            // 不声明就不隔离：插件用哪些宿主服务是运行时查表决定的，从权限看不出来。
            // 猜错的代价是真机上 12 个插件集体失败——比不隔离严重得多。
            return "没有声明在独立进程里运行（sidecar 加 \"isolation\": \"separate-process\"）"
        }
        val outside = permissions.filterNot { it in SUPPORTED_PERMISSIONS }
        if (outside.isNotEmpty()) {
            return "受限档暂时只支持工具类插件，它另外声明了 ${outside.joinToString(", ")}"
        }
        if (shortcutCount > 0) {
            return "受限档暂时不支持桌面快捷方式（子进程画不了入口图标）"
        }
        if (dependencies.isNotEmpty()) {
            return "受限档暂时不支持插件依赖（子进程只装一个模块）"
        }
        return null
    }
}
