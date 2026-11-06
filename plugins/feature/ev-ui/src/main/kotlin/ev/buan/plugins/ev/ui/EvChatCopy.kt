package ev.buan.plugins.ev.ui

import ev.buan.plugins.ev.ui.ui.EvSuggestion
import ev.buan.ui.EvGlyphKind

/**
 * 聊天界面用到的文案与建议：输入框占位、未接模型时的回显、首页那四张卡。
 *
 * 放在类外是因为它们是**内容**，不是状态：改一句话不该动到插件类，也不该出现在
 * `EvUiPlugin` 这份"装配说明书"里。
 */

/** Shown only when no plugin publishes AgentService: the original placeholder behaviour. */
    const val UNCONNECTED_PREFIX = "（未接模型）我收到了："

    /** Rotating composer placeholders (spec §7.3); one is picked per conversation. */
    val ComposerPlaceholders = listOf("交给 EV 去完成", "尽管问，带图也行")

    /**
     * Home suggestions, kept honest: every prompt maps to a capability that exists today
     * (the storage file tools, or the built-in browser panel). 分析屏幕 / 打开微信 return when
     * device-context and app-launch tools actually ship.
     */
    val Suggestions = listOf(
        EvSuggestion("列出我的文件", EvGlyphKind.Folder, "列出存储区里有哪些文件"),
        EvSuggestion("记一条笔记", EvGlyphKind.Create, "帮我把「今晚记得给花浇水」追加到 notes/memo.md"),
        EvSuggestion("你能做什么", EvGlyphKind.Help, "你现在有哪些工具，能帮我做什么？"),
        EvSuggestion("浏览网页", EvGlyphKind.World, ""),
    )
