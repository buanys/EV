# 往宿主界面里放东西：UI 槽位

插件的页面是自己画的（`AgentPage.Page`），宿主只负责容器和主题。UI 槽位是同一套规矩的另一半：
插件在**宿主已有的页面**里填一块内容，位置由宿主定，内容由插件定。

如果你要做的是"一个完整的页面"——自己的标题栏、自己的滚动、自己的状态——用
`AgentPage`（见 `host-api` 的 `AgentPage.kt`），不要用槽位。槽位是给"这一屏里还缺一块"用的。

## 六个孔

| 槽位 | 位置 | 可替换 |
| --- | --- | --- |
| `HomeHero` | 对话页顶部，第一条消息之上 | 是（只有一个赢家） |
| `HomeAboveComposer` | 输入框正上方 | 否 |
| `MessageActions` | 每条助手消息下方 | 否 |
| `SettingsSections` | 设置页，"关于"之前 | 否 |
| `DrawerItems` | 侧页四个入口之后 | 否 |
| `GlobalOverlay` | 整个应用的根，浮在最上层 | 是（只有一个赢家） |

"可替换"的含义：同一时刻只有一个贡献会被渲染（`priority` 最高者）。两个插件都往首页顶部塞东西，
叠在一起就是坏界面，所以这类槽位由宿主取一个赢家。其余槽位按 `priority` 降序全部渲染。

## 怎么注册

```kotlin
class MyPlugin : Plugin<Unit>, ManifestProvider {
    override val inject = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val slots = directory.lookup(UiSlotRegistry.Key) ?: return   // 老宿主没有它：不装就完了

        slots.contribute(owner = manifest.id, slot = UiSlot.HomeAboveComposer, priority = 10) {
            // 这里就是普通的 @Composable，用 ev.buan.ui 的组件，主题自动一致
            EvRow(
                title = "扫一下",
                summary = "把截图交给模型",
                onClick = { /* ... */ },
                trailing = { EvText("打开", kind = EvTextKind.Caption, tone = EvTone.Accent) },
            )
        }

        effect.collect { slots.revoke(manifest.id) }   // 停用/卸载时必须撤销
    }
}
```

四条规则：

1. **`owner` 用插件 id**。同一个 owner 对同一个槽位的第二次 `contribute` 是**替换**，不是追加——
   插件被重新激活时再注册一遍是正常路径。
2. **停用要 `revoke(owner)`**。不撤销的话，插件已经停了，界面上那块还在（点了会崩，因为它引用的
   服务已经没了）。用 `effect.collect { ... }`，宿主 dispose 时会走到。
3. **内容在宿主进程里执行**。`content` 是 `@Composable`，跑在宿主的组合里，Compose 没有 error
   boundary——这里抛出的异常会带走整个应用。别在里面做重活，别 `!!`，别假设任何服务一定在。
4. **只画内容，不定尺寸**。宿主给你一块宽度撑满的区域，纵向由内容决定。不要在槽位里
   `fillMaxSize()`——那会把整页撑开，而槽位只是页面的一部分。

## 宿主怎么渲染

`SlotHost(slot)`（`host-app/.../SlotHost.kt`）做三件事：读注册表、按 `version` 订阅变化、把内容堆起来。

```kotlin
SlotHost(UiSlot.HomeAboveComposer, Modifier.padding(horizontal = 16.dp))
```

`UiSlotRegistry.version` 是一个 `State<Int>`，`contribute` / `revoke` 每次自增。**这是槽位能"活"过来的
唯一原因**：Compose 看不见一个普通函数的返回值，没有这个可观察的版本号，插件注册之后界面不会变——
看起来就像"注册了但没显示"。

## 插件工程要开的东西

贡献 UI 的插件，构建脚本里至少三行：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")   // 版本钉在根构建里
}

android {
    buildFeatures { compose = true }
}

dependencies {
    // 只编译期：运行时的 ev.buan.ui 由宿主提供（已在 HostPackages.shared 里）
    compileOnly("io.github.buanys:ui-kit:0.0.1-SNAPSHOT")
}
```

`ev.buan.ui` 是宿主与插件共用的那一套组件，所以插件编出来的界面和宿主自带的长得一样——
这正是"不突兀"的来源。别为了省事去用 material3：它的形状、间距、颜色都不在这套 token 里。

## 样式

槽位里的内容和宿主自带的内容必须长得像一家人：

- 用 `ev.buan.ui` 的组件（`EvRow` / `EvText` / `EvButton` / `EvNotice` / `EvSection`），不要自己写 `Text`、
  不要硬编码颜色和字号。
- 颜色取 `EvColors`，语气用 `EvTone`；字号用 `EvTextKind`，别自己 `sp`。
- 间距交给宿主：`SlotHost` 已经在条目之间放了 8dp，内容自己不要再套一层 padding。

不做这几条不会崩，只会让插件看起来像贴上去的——这正是这套设计要避免的那件事。

## 已知边界

- 槽位内容不能跨进程：跑在子进程里的隔离插件（低信任档）**没有** `UiSlotRegistry` 这条服务，
  `lookup` 会返回 null。它们的成果通过工具结果回到对话里，不是通过界面。
- `GlobalOverlay` 只有一个赢家。要做多个浮层请在同一次 `contribute` 里自己组合，别指望并排。
- 目前没有"槽位里再开槽位"：槽位内容不会被宿主再次分发。
