package ev.buan

/**
 * 插件在 sidecar 里声明的一个桌面入口。
 *
 * 为什么放在 sidecar 而不是 `plugin-api` 契约里：快捷方式是宿主的能力，
 * 插件只是声明"我可以被这样打开"。契约只加不改的规矩也提醒过——往
 * `PluginManifest` 里加字段会让所有已编译插件重新编译一次，而 sidecar 是纯元数据，
 * 读取方只有宿主自己。
 */
data class ShortcutDeclaration(
    /** 插件内唯一；桌面快捷方式的系统 id 会用它拼出来。 */
    val id: String,
    /** 桌面上显示的名字，shortLabel 有字数限制，太长会被系统截断。 */
    val label: String,
    /**
     * 图标来源：`EvGlyphKind` 的枚举名（如 `Globe`）。
     *
     * 插件是 dex，没有自己的 drawable 资源，所以只能声明一个内置图形由宿主画成位图。
     * 认不出的名字退回到 EV 的启动图标——总比没有强。
     */
    val icon: String? = null,
)
