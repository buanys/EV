# Eta 界面设计提取清单（供 EV 移植）

> 生成方式：只读代码扫描，先 grep 再逐段读。**每一条都带 `文件:行号`。**
> 路径前缀统一为：`app/src/main/kotlin/io/github/mangi/eta/`（下文所有相对路径都拼在这个前缀后面）。
> 库内数值来自构建缓存中的 miuix 制品，不是仓库源码，已单独标注来源。
>
> 一条重要前提：**Eta 没有自己的一套颜色常量表。** 除少数硬编码值，几乎所有颜色、字号都来自 `MiuixTheme` 的 Material 式色彩角色（`primary` / `surfaceContainerHigh` / `onSurfaceVariantSummary` …），由 `ThemeController` 在运行时按「亮暗 + 是否需要动态取色 + 调色板风格」生成。所以"亮/暗两套颜色"在代码里不是两张表，而是一组**角色 + 种子色**。下面第 1.1 给出真正硬编码的颜色，1.2 给出主题生成链，1.3 给出被使用的角色清单。

---

## 0. 一句话总览

| 维度 | 结论 |
|---|---|
| 框架 | Jetpack Compose + miuix-kmp 0.9.4（不是 Material3，`Scaffold`/`Card`/`Text`/`Icon` 全用 miuix 版本） |
| 形状体系 | miuix **squircle**（`squircleSurface` / `squircleBorder` / `squircleClip`）为主，少数地方用 `RoundedCornerShape` |
| 动效体系 | 弹簧优先，几乎不用补间（唯一例外见 1.6）；三条硬规则写在 `ui/app/EtaMotion.kt:12-20` |
| 视觉签名 | 输入框「液态玻璃 + 边缘流光 + 抓焦点渐变描边」，是整套设计里唯一重度自定义的部件 |
| 消息形态 | 用户消息=右侧气泡（贴右下角缺角）；助手消息=**无气泡**的裸 Markdown 文本 |
| 深色策略 | 深色下侧栏升一档到 `surfaceContainer`，聊天区留 `surface`；"纯黑"模式直接把 background/surface 改 `Color.Black` |

---

## 1. Design Token

### 1.1 硬编码颜色（亮/暗无关，代码里写死的）

| 语义 | 值 | 位置 |
|---|---|---|
| 品牌蓝（非动态取色时的主色） | `#0080FF` (`EtaPreferenceColors.Blue`) | `ui/components/EtaPreferenceStyle.kt:28` |
| 设置页浅色背景覆盖 | `#F0F1F2` | `ui/components/EtaPreferenceStyle.kt:49` |
| 设置页图标橙 | `#FF7700` | `ui/components/EtaPreferenceStyle.kt:30` |
| 状态成功 | `#00BD13` (`StatusSuccess`) | `ui/components/StatusColors.kt:12` |
| 状态警告 | `#FFB200` (`StatusWarning`) | `ui/components/StatusColors.kt:13` |
| 用户气泡-深色底（仅 assistantOverlay 浮层用） | `#37393D` | `ui/components/ChatMessageItem.kt:324` |
| 用户气泡-浅色底（同上） | `#E5E7EA` | `ui/components/ChatMessageItem.kt:326` |
| Markdown 行内代码兜底底/链接兜底色 | `#1F808080` / `#3482FF` | `ui/markdown/MarkdownInline.kt:34` |
| Markdown 警告框紫 | `#8B5CF6` | `ui/markdown/MarkdownContent.kt:328` |
| 收藏星黄 | `#FFC94D` | `ui/screens/characters/CharacterPortraitCard.kt:145` |
| 附件预览删除点底色 | `Black @ 58%` | `ui/components/AgentChatInputBar.kt:716` |
| 字标渐变 6 组（按名字散列） | `#F58FA8→#C56CD6`、`#7FA7F5→#6A6FE0`、`#6CC9B4→#3E9FB8`、`#F5B36C→#E07A5F`、`#A48BEB→#6C63C9`、`#8CC97A→#4FA37C` | `ui/components/CharacterAvatar.kt:131-138` |

### 1.2 强调色种子（动态取色关闭时才生效）

| 选项 | 种子色 | 位置 |
|---|---|---|
| SYSTEM / BLUE | `#3482FF` | `ui/app/AgentAppTheme.kt:115` |
| PURPLE | `#6750A4` | `:116` |
| PINK | `#B0006D` | `:117` |
| RED | `#BA1A1A` | `:118` |
| ORANGE | `#B65D00` | `:119` |
| YELLOW | `#7D5700` | `:120` |
| GREEN | `#006D3B` | `:121` |
| TEAL | `#006A6A` | `:122` |

生成链：`colorSchemeMode`(Light/Dark/System × Monet) → `ThemeController(colorSpec = Spec2025, paletteStyle)` → `currentColors()` → 纯黑叠加（`ui/app/AgentAppTheme.kt:35-74`）。
- `ThemeColorSpec.Spec2025`（**spec 名请 EV 对齐 miuix 版本**）：`:59`
- 8 种调色板风格映射 `TONAL_SPOT/NEUTRAL/VIBRANT/EXPRESSIVE/RAINBOW/FRUIT_SALAD/MONOCHROME/FIDELITY/CONTENT` → miuix `ThemePaletteStyle.*`：`:102-112`
- 纯黑模式：`colors.copy(background = Color.Black, surface = Color.Black)`：`:66-73`
- 设置页浅色专用覆盖（非 monet 且背景亮度 > 0.5 时，背景 `#F0F1F2` + 主色 `#0080FF`）：`ui/components/EtaPreferenceStyle.kt:44-54`

### 1.3 被使用的 miuix 色彩角色（**不是自定义 token，是角色清单**）

出现频率最高的角色，EV 需要一一对应：`primary`、`onPrimary`、`surface`、`surfaceContainer`、`surfaceContainerHigh`、`surfaceContainerHighest`、`background`、`onBackground`、`onSurface`、`onSurfaceVariantSummary`、`onSurfaceVariantActions`、`outline`、`dividerLine`、`error`、`onError`、`disabledOnSurface`、`disabledOnSecondaryVariant`。
代表用法：`ui/components/StatusColors.kt:14-16`（error/primary/onSurfaceVariantSummary 语义化）、`ui/components/ConversationPanePanel.kt:120-124`、`ui/components/EtaPreferenceStyle.kt:71-103`。

> ⚠️ 待老大定：EV 若不用 miuix，这些角色必须自己造一份等价表（Material3 有同名近似角色，但 `onSurfaceVariantSummary`、`surfaceContainerHighest`、`dividerLine` 是 miuix 专名）。

### 1.4 字号

**仓库内显式覆盖的字号**（这是 EV 真正需要照抄的部分）：

| 用途 | 字号 / 行高 | 位置 |
|---|---|---|
| Markdown 正文（回答） | 16sp / 行高 26sp | `ui/markdown/MarkdownStyle.kt:63-64` |
| Markdown 正文（思考） | 14sp / 行高 22sp | 同上 |
| 标题 H1 | 20sp / 28sp / Bold | `ui/markdown/MarkdownStyle.kt:70` |
| 标题 H2 | 18sp / 26sp / Bold | `:71` |
| 标题 H3 | 17sp / 25sp / SemiBold | `:72` |
| 标题 H4 | 继承 16sp / SemiBold | `:73` |
| 思考态标题 | 15sp / SemiBold | `:76` |
| 代码等宽 | 13sp / 20sp（回答）、12sp / 18sp（思考） | `:82-87` |
| 表格 | 14sp / 21sp（回答）、13sp / 19sp（思考） | `:88` |
| 输入框正文 | `body1` + 行高固定 **22sp** | `ui/components/AgentChatInputBar.kt:419` |
| 状态胶囊文字 | `footnote1` 但压到 **11sp** | `ui/components/ConversationStatusCapsules.kt:58` |
| 字标首字母 | `size.value * 0.42`sp | `ui/components/CharacterAvatar.kt:125` |
| 行高常量 | `ANSWER_LINE_HEIGHT_SP = 26`、`THINKING_LINE_HEIGHT_SP = 22` | `ui/markdown/MarkdownStyle.kt:124-125` |

**miuix `TextStyles` 各档字号**（反汇编 `miuix-ui.aar` 得来，非仓库源码；EV 若不引 miuix 需自建）：`main 17 · paragraph 17 · body1 16 · body2 14 · button 17 · footnote1 13 · footnote2 11 · headline1 17 · headline2 16 · subtitle 14 · title1 32 · title2 24 · title3 20 · title4 18`（单位 sp，行高均未指定）。
来源：`/root/.gradle/caches/modules-2/files-2.1/top.yukonga.miuix.kmp/miuix-ui-android/0.9.4/aeb8a229f057a1368c9d3dcf5bb766cd9c64d8cc/miuix-ui.aar` → `top/yukonga/miuix/kmp/theme/TextStylesKt.class`。

### 1.5 圆角

| 元素 | 值 | 形态 | 位置 |
|---|---|---|---|
| 设置卡片 / 通用卡片 | **24dp** | squircle | `ui/components/EtaCard.kt:14` |
| 用户消息气泡 | 20/20/20/**6**（右下缺角，编辑态恢复 20） | squircle | `ui/components/ChatMessageItem.kt:385-392` |
| 用户消息浮层版（overlay） | 10dp | Rounded | `:383` |
| 输入框容器 | **20dp** | Rounded（不是 squircle） | `ui/components/AgentChatInputBar.kt:127` |
| 思考块 / 工具卡外包 | 12dp (`ChatCardCorner`) | squircle | `ui/components/ChatMessageItem.kt:756, 866-874` |
| 代码块 | 12dp | squircle | `ui/markdown/MarkdownContent.kt:186` |
| 表格 | 12dp（描边 0.5dp） | squircle | `ui/markdown/MarkdownContent.kt:457-458` |
| Markdown 引用/提示块 | 12dp | squircle | `ui/markdown/MarkdownContent.kt:277` |
| 工具行 hover/点击热区 | 10dp | Rounded | `ui/components/AgentToolActivity.kt:140, 242` |
| 工具详情块 | 10dp | squircle | `ui/components/AgentToolActivity.kt:336-338, 421-423, 510-517` |
| 附件缩略图 | 12dp | Rounded / squircle | `ui/components/AgentChatInputBar.kt:692`、`ui/components/AgentChatFileAttachments.kt:217-224` |
| `EtaChoiceChip` 胶囊 | 16dp | squircle | `ui/components/EtaChoiceChip.kt:71-72` |
| `ToolChip` 小标签 | 8dp | Rounded | `ui/components/ToolChip.kt:21` |
| 通用按钮 | **22dp** | Rounded | `ui/components/EtaControls.kt:24` |
| 侧栏会话行 | 12dp | Rounded | `ui/components/ConversationPanePanel.kt:105, 414` |
| 推荐卡片（首页） | 16dp | squircle | `ui/components/AgentChatBody.kt:1185-1189` |
| 模型控件 | 17dp / 12dp | squircle | `ui/components/AgentChatModelControls.kt:84`、`AgentModelPicker.kt:370` |
| 对话框 | **32dp**（miuix `DialogDefaults.CornerRadius`） | miuix | `ui/components/EtaControls.kt:67, 91` |
| miuix 卡片默认圆角（被 Eta 覆盖成 24） | 16dp | miuix | 同上 aar → `CardDefaults.class` |

### 1.6 间距

| 语义 | 值 | 位置 |
|---|---|---|
| 设置页左右边距 | 16dp (`SidePadding`) | `ui/components/EtaPreferenceStyle.kt:35` |
| 设置组间距 | 16dp | `:36` |
| 设置图标尺寸 / 图标-文字间距 | 24dp / 16dp | `:37-38` |
| 设置行最小高度 | 52dp | `:41` |
| 设置组标题内缩 | start/end 32dp，上 4 下 8 | `:72` |
| 设置行内边距 | 横向 16dp，纵向 12dp | `ui/components/EtaPreferenceRow.kt:121` |
| 设置分割线 | 0.33dp，`onBackground @ 10%`，缩进 = 16+24+16 | `ui/components/EtaPreferenceStyle.kt:98-104` |
| 聊天气泡外边距 | 横 20dp、纵 8dp（overlay: 16/4） | `ui/components/ChatMessageItem.kt:332-335` |
| 聊天气泡内边距 | 横 16dp、纵 12dp（overlay: 12/8） | `:406-409` |
| 气泡最大宽度 | 320dp | `:380` |
| 助手消息内边距（探索块） | 横 20dp、纵 4dp | `ui/components/ChatMessageItem.kt:865` |
| 输入框容器内边距 | 横 10dp、纵 8dp | `ui/components/AgentChatInputBar.kt:409` |
| 输入框文本区内边距 | 横 8dp、纵 6dp，最小高 40dp | `:414-415` |
| 输入框底部安全区 | 横 16dp、下 12dp + `navigationBarsPadding` | `ui/components/AgentChatBody.kt:1001-1002` |
| 输入栏操作按钮命中区 | **48dp × 48dp**，可见图标 24dp | `ui/components/AgentChatFileAttachments.kt:66-70` |
| 输入栏动作按钮间距 | 2dp（`Spacer`） | `ui/components/AgentChatInputBar.kt:480, 499, 504` |
| 工作行高度 / 左侧槽宽 / 槽间距 / 节点圆 | 32dp / 22dp / 8dp / 20dp | `ui/components/AgentWorkProcess.kt:468-471` |
| 回答操作按钮 | 36dp，左偏 11dp 对齐 | `ui/components/ChatMessageItem.kt:760-761` |
| 侧栏内边距体系 | Pane 横 16、Top 6、底部 8、行最小高 48、行间距 4、行内横 12/纵 12、选中点 6+10 | `ui/components/ConversationPanePanel.kt:97-112` |
| 侧栏宽度 | 最小(maxWidth×0.84, 340dp) | `ui/components/ConversationSidePaneScaffold.kt:59-60` |
| 宽屏断点 / 内容最大宽 | 600dp / 800dp | `ui/layout/AdaptivePageLayout.kt:20-21` |
| 对话框内/外边距 | 24dp / 16dp；最大宽 420dp | `ui/components/EtaControls.kt:27-28`、miuix `DialogDefaults` |
| 底部磨砂渐变高度 | 24dp | `ui/components/AgentChatBody.kt:1039` |
| 开关尺寸 | 强制 44dp × 24dp | `ui/components/EtaControls.kt:110-111` |

### 1.7 阴影

全仓库只有 **两处** 阴影，都挂在输入区：

| 元素 | 参数 | 位置 |
|---|---|---|
| 输入框容器 | `Shadow(radius = 8.dp, color = Black, alpha = 0.08f)` | `ui/components/AgentChatInputBar.kt:389-396` |
| 底部计划条/审批卡 | `Shadow(radius = 8.dp, color = Black, alpha = 0.08f)` | `ui/components/AgentChatBody.kt:758-760` |

> 其余"立体感"全部靠 blur backdrop + 描边渐变 + 亮度分档做出来，没有 elevation 阴影。EV 若用 Material 的 elevation，会得到完全不同的观感。

### 1.8 动画：时长与曲线

**基础规格（三条硬规则：只用弹簧 / 空间与效果分离 / 尊重"移除动画"）** —— `ui/app/EtaMotion.kt:12-33`

| 名称 | 参数 | 用途 | 位置 |
|---|---|---|---|
| `EtaMotion.spatial()` | `spring(dampingRatio = 0.6, stiffness = StiffnessMediumLow)` | 位置/尺寸/旋转/形状 | `ui/app/EtaMotion.kt:23-24` |
| `EtaMotion.spatialFast()` | `spring(0.6, StiffnessMedium)` | 小元素（图标/开关/指示器） | `:27-28` |
| `EtaMotion.effects()` | `spring(DampingRatioNoBouncy, StiffnessMedium)` | 颜色/透明度（必须临界阻尼） | `:31-32` |
| `SpatialDamping` | `0.6f` | 唯一回弹来源 | `:35` |
| Reduce Motion 判定 | `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` | 无障碍 | `:38-50` |
| `etaSpatial()` / `etaEffects()` | 命中 Reduce Motion 时返回 `snap()` | Composable 入口 | `:57-63` |

**具体时长**（补间仅出现在无限循环与渐隐）：

| 用途 | 时长 / 曲线 | 位置 |
|---|---|---|
| 输入框流光平移 | **5600ms, LinearEasing, 无限循环** | `ui/components/AgentChatInputBar.kt:258, 779` |
| 流光淡出 | **700ms tween**（`shimmerRunning → 0`） | `:247, 780` |
| 纯黑首页光晕漂移 | **10000ms, LinearEasing, 无限循环** | `:273, 787` |
| 输入框聚焦描边渐变 | `EtaMotion.effects()`（弹簧） | `:229-233` |
| 发送/停止图标切换 | `fade±scale(0.72)` + `EtaMotion.effects()` | `:557-565` |
| 打字点（3 点） | 600ms，延迟 `index*150`ms，Reverse | `ui/components/ChatMessageItem.kt:146-156` |
| 运行脉冲（alpha 0.58→1） | 820ms `FastOutSlowInEasing`，Reverse | `ui/components/ChatMessageItem.kt:178-186` |
| 工作节点脉冲（0.55→1） | 820ms `FastOutSlowInEasing`，Reverse | `ui/components/AgentWorkProcess.kt:381-393` |
| 工具标题高光扫过 | **1600ms LinearEasing** 无限 | `ui/components/AgentWorkProcess.kt:414` |
| 展开箭头旋转 0°→90° | `spring(StiffnessMediumLow)` | `ui/components/AgentWorkProcess.kt:452-456` |
| 侧栏停靠弹簧 | `dampingRatio = 1f, stiffness = 146f` | `ui/components/ConversationSidePaneScaffold.kt:62-63` |
| 侧栏判定阈值 | 位置 0.5、可见性 0.5px | `:64-65` |
| 模型选中确认延迟 | 140ms | `ui/components/AgentModelPicker.kt:433` |
| 后端追踪线流动 | 1600ms LinearEasing | `ui/components/AgentWorkProcess.kt:414` |

---

## 2. 组件清单

### 2.1 用户消息气泡 `UserMessageBubble`

- 结构：`Row(End)` → `TooltipBox`（长按浮出 复制/编辑/删除）→ `Column(widthIn max 320dp)` → squircle 气泡 → 图片行 / 文件引用流 / 正文 / "已编辑"。
- 组件：miuix `TooltipBox` + `RichTooltip` + `rememberTooltipState(isPersistent = true)`、`squircleSurface`、`squircleBorder`、`SelectionContainer` + miuix `Text`。
- 关键参数：气泡底 `surfaceContainerHigh`；圆角 20/20/20/**6**（右下收角=气泡尾）；编辑态右下回 20 且加 `squircleBorder(1dp, primary, 20dp)`；最大宽 320dp；正文 `body1`；图片 100dp / 12dp 圆角；外距 20/8，内距 16/12。
- 状态：常态（右尾角）/ 编辑中（四角 20 + 主色描边）/ 长按（tooltip + `HapticFeedbackType.LongPress`）/ 复制成功（`HapticFeedbackType.Confirm`）。
- 位置：`ui/components/ChatMessageItem.kt:298-460`（圆角见 `:385-392`，描边见 `:396-405`，触觉见 `:315-318, 351`）。
- 备注：`assistantOverlay` 模式（悬浮助手）换 `RoundedCornerShape(10dp)` + 硬编码 `#37393D`/`#E5E7EA`，是另一套视觉（`ui/components/ChatMessageItem.kt:323-327, 383`）。

### 2.2 助手消息块 `AgentMessageBlock`

- 结构：**无气泡**。可选角色回复头 → `AITypingIndicator`（流式首字前）→ `StreamingMarkdown` / `StaticMarkdown` → 显现完成后淡入操作行（复制/朗读/重新生成/候选切换）。
- 组件：`ui/markdown/StreamingMarkdown`、`StaticMarkdown`（自研渲染器，基于 `org.intellij.markdown`）、`AnimatedVisibility` + `etaEffects()/etaSpatial()`。
- 关键参数：正文 `MarkdownTone.Answer`（16sp / 行高 26sp）；操作行按钮 36dp、左偏 11dp 对齐工作区中线；操作行 `enter = fadeIn(etaEffects()) + expandVertically(etaSpatial())`。
- 状态：流式中（光标 + 打字点）/ 显现中（`SmoothTextReveal`）/ 完成（操作行出现）/ 可多候选。
- 位置：`ui/components/ChatMessageItem.kt:547-720`（操作行 `:641-660`，正文分支 `:603-638`）。

### 2.3 工具调用折叠块 `ToolActivityRow` / `ToolGroupRow`

- 结构：`Column` → 可点击头行（`.heightIn(32dp)` + `clip(10dp)` + 可选 `clickable`) → 左侧标题列（标题 + 副标题）、右侧耗时 + 展开箭头 → `AnimatedVisibility(expanded)` → `ToolActivityDetail`。
- 组件：自研 `ShimmerText`（运行中高光）、`ExpandChevron`、miuix `Text`/`Icon`；分组用 `pluralStringResource` 显示"· N 次"。
- 关键参数：头行最小高 32dp、圆角 10dp、纵向内距 6dp；副标题 `footnote2`；终端类工具副标题换 `FontFamily.Monospace`；失败色 `StatusError`；次要文字 `onSurfaceVariantSummary @ 75%`；耗时常量阈值 **3 秒**（`RUNNING_TIMER_THRESHOLD_SECONDS = 3`）；连续同工具自动合成一组。
- 状态：**运行中**（标题 `onSurface` + `ShimmerText` 扫光 1600ms + 计时）/ **成功**（标题退 `onSurfaceVariantSummary`）/ **失败**（副标题 `StatusError`）/ **展开**（箭头转 90° + 详情区）/ **分组**（标题带次数，失败的仍可单独展开）。
- 位置：`ui/components/AgentToolActivity.kt:135-205`（单次）、`:210-280`（分组）、`:177-183`（计时）、`:393`（阈值）。

### 2.4 输入框（玻璃 + 流光）`AgentChatInputBar`

这是全项目最复杂、最值得单独移植的部件。

- 结构（自下而上）：
  1. `Column` 根
  2. 语音反馈 / 待发文件条 / 待发图片条 / 编辑提示 / 斜杠命令补全（各自 `AnimatedVisibility`）
  3. `Box` 记录容器顶部窗口坐标（供弹层避让）→ `Column`
  4. `homeGlow`（仅纯黑首页空态）→ `dropShadow` → `liquidGlass` → 内边距 10/8
  5. 文本区（占位 `Text` + `BasicTextField`，`defaultMinSize(40dp)`）
  6. 操作行：取消编辑/附件 → 思考强度 → 弹性空隙 → 上下文占用 → 语音 → 模型选择 → 打断/停止/发送
- 组件：`BasicTextField`（`rememberTextFieldState` + `TextFieldLineLimits.MultiLine(1, 6)`）、miuix `IconButton`（`minWidth/minHeight = 48dp`）、`AnimatedContent`、`WindowListPopup` + `DropdownImpl` + `LocalDismissState`。
- 关键参数：
  - 容器形状 `RoundedCornerShape(20dp)`（**不是 squircle**，`AgentChatInputBar.kt:127`）
  - 玻璃：`tint = White @ glassTintAlpha`、`blurRadius = glassBlurRadiusDp`、边缘强调色 `primary`
  - 文本 `body1` + 行高 22sp，光标 `primary`
  - 发送按钮：可见圆直径 24dp（`SendButtonVisualSize = ChatInputActionIconSize`），命中区 48dp；图标 16dp，停止图标 10dp
  - 玻璃滑块换算：模糊 `8dp + 32 × c`（默认 0.5 → 24dp），着色 `0.12 − 0.08 × c`（默认 0.08），首页空态 ×5（`data/model/AppearanceSettings.kt:20-34`）
- 状态：
  - **常态**：白雾玻璃，描边左上 0.55 → 0.12 → 0.03 alpha
  - **聚焦**：`focusProgress` 0→1，描边 lerp 到强调色 0.92/0.45/0.10（只画描边，不重组）
  - **运行中（流光）**：5600ms 一条光带从左上滑到右下，峰值 0.85，带宽 = 高度 × 1.15
  - **纯黑首页空态**：底部蓝色径向光晕 0.18，直径 = 宽 × 0.6，漂移 ±10dp/±4dp（垂直 2 倍频），10s 一圈
  - **降级**：模糊不可用 → 实色 `surfaceContainer`；首页空态再乘 0.4 alpha
  - **Reduce Motion**：`SolidWhenMotionReduced = true` 时整体退回实色，流光关闭
- 位置：`ui/components/AgentChatInputBar.kt:132-598`；玻璃挂载 `:373-409`；状态计算 `:207-281`；按钮色 `:541-592`；常量 `:123-127, 773-789`。

### 2.5 顶栏 `AdaptiveTopAppBar`

- 结构：宽屏（≥600dp）→ miuix `SmallTopAppBar`（固定小标题）；手机 → miuix `TopAppBar`（可折叠大标题）。
- 组件：miuix `TopAppBar` / `SmallTopAppBar` / `ScrollBehavior`。
- 关键参数：`color` 默认 `surface`；手机端聊天页实际传的是 `topBarContainerColor(backdrop)`（有模糊时 `Transparent`，见下）。
- 状态：滚动折叠（大标题→小标题）/ 静止。
- 位置：`ui/components/AdaptiveTopAppBar.kt:13-45`；断点 `ui/layout/AdaptivePageLayout.kt:20-25`。

**顶栏磨砂 `TopBarBackdrop`**（独立部件，值得一起移植）：
- `textureBlur(blurRadius = 25f(px), surface @ 80%)`；
- 或渐进式 `progressiveTextureBlur(ProgressiveBlur.Top.copy(curve = 2.2f), blurRadius = 10f(px), surface @ 30%)`；
- 模糊不可用时退 `Modifier.background(surface)`。
- 位置：`ui/components/TopBarBackdrop.kt:23-80`（常量 `:77-80`）。

### 2.6 侧栏 `ConversationSidePaneScaffold` + `ConversationPanePanel`

- 结构：外层 `AnchoredDraggable` 推挤容器（前台内容被平移，不是抽屉遮盖），内层 `Surface` → 固定头部（Logo 40dp + 应用名 `headline2` Bold + 能力徽章 + 新建按钮）→ 固定搜索 → 分组历史列表 → 固定底部工具入口。
- 组件：`AnchoredDraggable` + 自定义 fling/spring、miuix `Surface`、`SurfaceContainerHighest` 选中底、`combinedClickable`（长按动作菜单 `WindowListPopup`）。
- 关键参数：宽度 `min(maxWidth × 0.84, 340dp)`；遮罩 alpha 0.16；停靠 `damping 1f / stiffness 146f`；行高 ≥48dp、圆角 12dp、行内距 12/12；选中底 `surfaceContainerHighest`；`CharacterAvatar` 36dp；长按触发 `HapticFeedbackType.LongPress`。
- 背景色规则：浅色 `surface`，深色升一档 `surfaceContainer`（否则展开后与聊天区糊成一片）—— `ui/components/ConversationPanePanel.kt:114-124`。
- 位移数学：`progress = offset/width`，`panelOffset = offset − width` —— `ui/components/ConversationPaneMotion.kt:9-17`。
- 位置：`ui/components/ConversationSidePaneScaffold.kt:59-66, 95-300`；面板 `ui/components/ConversationPanePanel.kt:97-124, 156-200, 409-434`。

### 2.7 设置页条目

三层结构：`EtaPreferenceGroupTitle`（组标题）→ `EtaPreferenceGroup`（= 24dp 圆角 Card，`insideMargin = 0`）→ `EtaPreference` / `EtaArrowPreference`（行）。

- 结构：`EtaPreferenceRow` 用**自定义 `Layout`**（不是 Row）实现"图标固定列 + 文字列 + 右侧动作区"，动作区宽度约束为剩余宽度的 45%（`ui/components/EtaPreferenceRow.kt:143-161`）。
- 组件：miuix `Card` / `CardColors` / `HorizontalDivider` / `Icon` / `Text` / `MiuixIcons.Basic.ArrowRight`。
- 关键参数：行最小高 52dp；图标槽 24dp（少数"满幅轮廓"图标光学校正到 22dp：`Extension` / `TheaterComedy` / `MenuBook`，见 `ui/components/EtaPreferenceStyle.kt:84-87`）；标题 `body1` + `FontWeight.Medium`；摘要 `body2`；箭头 8×14dp、`onBackground @ 30%`；分割线 0.33dp 缩进对齐文字起点。
- 状态：常态 / `holdDownState`（`onBackground @ 6%` 底）/ `enabled = false`（`disabledOnSurface`）/ 箭头行。
- 位置：`ui/components/EtaPreferenceRow.kt:34-168`、`ui/components/EtaPreferenceStyle.kt:44-105`、`ui/components/EtaCard.kt:13-57`。
- 注意：整个设置页被 `EtaPreferenceTheme` 包了一层，浅色下会**改背景色和主色**（见 1.2），这是设计上刻意的例外。

### 2.8 对话框

- 两种：`EtaWindowDialog`（真窗口）与 `EtaOverlayDialog`（应用内浮层），参数完全一致。
- 组件：miuix `WindowDialog` / `OverlayDialog`。
- 关键参数：背景 `surfaceContainer`；圆角 **32dp**；内边距 24dp；外边距 16dp；最大宽 420dp。
- 按钮行 `MiuixDialogActions`：取消左 / 确认右，各占 1/2 宽，间距 8dp；`destructive` 时确认按钮换 `error` 配色。
- 位置：`ui/components/EtaControls.kt:52-98`、`ui/components/MiuixDialogActions.kt:27-63`；散落调用点（`cornerRadius = DialogDefaults.CornerRadius`）：`ui/app/AgentAppRoot.kt:857, 870, 899, 919, 947`。

### 2.9 行内代码 / 代码块 / 表格

- 行内代码：`AnnotatedString` span，背景 `onSurface @ 7%`（`MarkdownStyle.kt:97`），兜底 `#1F808080`（`MarkdownInline.kt:34`）；**只有背景色，没有内边距和圆角**（`MarkdownInline.kt:242, 258`）。
- 代码块：`Column` + `squircleSurface(surfaceContainer, 12dp)` → 头部行（语言名 `footnote2`、复制按钮，内距 start 14/end 4/top 2）→ 等宽正文 13sp/20sp（`MarkdownContent.kt:181-200`）。
- 表格：`Layout` 自绘网格 + `squircleBorder(0.5dp, outline@60%, 12dp)`，表头填 `surfaceContainer`，横线 0.5dp，横向可滚动（`MarkdownContent.kt:442-470`）。
- 状态：代码块常态 / 复制成功（图标变 `Check`）；流式时随 `SmoothTextReveal` 逐字显现（`MarkdownContent.kt:170-175`）。

### 2.10 其他值得注意的小件

| 组件 | 说明 | 位置 |
|---|---|---|
| `EtaChoiceChip` | 胶囊筛选器；未选中只有 1dp 描边无底，选中填 `primary @ 12%`；圆角 16dp；内距 14/7 | `ui/components/EtaChoiceChip.kt:31-76` |
| `ToolChip` | 8dp 圆角小标签，`surfaceContainerHigh` 底，内距 8/4 | `ui/components/ToolChip.kt:14-31` |
| `StatusCapsule` | `CircleShape` 胶囊，`surfaceContainer` 底，字号强制 11sp，内距 8/5，整行最大宽 232dp | `ui/components/ConversationStatusCapsules.kt:45-59` |
| `CharacterMonogram` | 按名字散列取双色渐变，白字 0.42×size | `ui/components/CharacterAvatar.kt:114-138` |
| `SuggestionCard` | 首页推荐卡，`squircleSurface(surface, 16dp)` + `0.5dp outline@50%` 描边，内距 14/14 | `ui/components/AgentChatBody.kt:1177-1200` |
| `EtaTextButton` / `EtaSwitch` | 按钮 22dp 圆角 / 44dp 高；开关**被强制缩放**到 44×24dp | `ui/components/EtaControls.kt:31-50, 100-119` |

---

## 3. 自定义 Modifier / Composable 工具函数（给完整源码位置）

| 名称 | 类型 | 作用 | 源码位置 | 依赖 |
|---|---|---|---|---|
| **`Modifier.liquidGlass(...)`** | Modifier 扩展 | 核心视觉：模糊底 + 内部光泽 + 渐变现描边 + 流动光带，全部零重组 | `ui/components/LiquidGlassSurface.kt:45-152` | miuix `textureBlur` / `BlurColors` / `BlendColorEntry` / `LayerBackdrop`；Compose `drawWithCache` / `createOutline`。常量 `:154-183` |
| **`Modifier.homeGlow(...)`** | Modifier 扩展 | 纯黑首页输入框后方的蓝色径向光，正弦漂移 | `ui/components/AgentChatInputBar.kt:741-770` | `drawBehind` + `Brush.radialGradient`；参数 `:783-787` |
| **`ShimmerText`** | Composable | 运行中标题的扫光高光（`BlendMode.SrcAtop` + 离屏层） | `ui/components/AgentWorkProcess.kt:399-444` | `graphicsLayer{CompositingStrategy.Offscreen}` + `drawWithContent`；带宽 `max(width×0.35, 72dp)`，周期 1600ms |
| **`ExpandChevron`** | Composable | 14dp 箭头，展开旋转 90° | `ui/components/AgentWorkProcess.kt:446-465` | `animateFloatAsState` + `spring(StiffnessMediumLow)` |
| **`rememberActivePulse`** | Composable | 运行中 alpha 呼吸 0.58→1（历史节点返回常量 1，不持有动画） | `ui/components/ChatMessageItem.kt:171-188` | `rememberInfiniteTransition` |
| **`rememberDrawPhasePulse`** | Composable | 同上，0.55→1，供时间线节点用 | `ui/components/AgentWorkProcess.kt:380-393` | 同上 |
| **`EtaMotion` / `etaSpatial` / `etaEffects` / `rememberReducedMotion`** | object + Composable | 全局动效规格入口 | `ui/app/EtaMotion.kt:21-63` | `android.provider.Settings.Global` |
| **`rememberTopBarBackdrop` / `TopBarBackdrop` / `captureForTopBar` / `topBarContainerColor`** | Composable + Modifier | 顶栏磨砂（高斯 / 渐进两种） | `ui/components/TopBarBackdrop.kt:23-80` | miuix `rememberLayerBackdrop` / `layerBackdrop` / `progressiveTextureBlur` / `isRuntimeShaderSupported` |
| **`AITypingIndicator`** | Composable | 三点等待动画（6dp 圆点，间隔 4dp） | `ui/components/ChatMessageItem.kt:138-165` | `rememberInfiniteTransition` |
| **`Modifier.squircleSurface / squircleBorder / squircleClip`** | 第三方 Modifier | 全站形状基元（超椭圆，非普通圆角） | 来自 `miuix-squircle.aar`，仓库内只有调用点 | miuix `top.yukonga.miuix.kmp.squircle` |
| **`rememberMarkdownStyle` / `markdownBlockGap` / `markdownCompactGap`** | Composable + 纯函数 | Markdown 全部排版规格（字号/间距/语义间距表） | `ui/markdown/MarkdownStyle.kt:55-126` | miuix `MiuixTheme` |
| **`EtaPreferenceTheme`** | Composable | 设置页专用主题覆盖（浅色改背景+主色） | `ui/components/EtaPreferenceStyle.kt:44-54` | `LocalAppearanceSettings` |
| **`conversationPaneMotion`** | 纯函数 | 侧栏位移→进度/偏移换算 | `ui/components/ConversationPaneMotion.kt:9-17` | 无 |
| **`Modifier.smoothTextReveal`** | Modifier 扩展 | 流式逐字显现 | `ui/markdown/SmoothTextReveal.kt`（未逐行读，见第 7 节） | — |

**玻璃模糊的两条实现约束（改动前必读，写在源码注释里）**：
1. miuix 的 `blurRadius` 是**裸像素**，内部不做 dp→px（这也是为什么入口收 `Dp` 再自己换算）—— `ui/components/LiquidGlassSurface.kt:33-40`，上限 150px 硬夹（`:67, 158`）。
2. 描边**必须自己画**，不能用 `Modifier.border`；流动光相位要在绘制阶段读，否则每帧重组整条输入栏（`:38-40, 105-150`）。
3. 每一层都必须带 `shape`，否则四角露直角（`:42-43`）。

---

## 4. 依赖

| 类别 | 内容 | 位置 |
|---|---|---|
| UI 框架 | miuix-kmp **0.9.4**（`miuix-ui-android` / `-icons` / `-blur` / `-nav` / `-preference`） | `gradle/libs.versions.toml:9, 36-40`；`app/build.gradle.kts:154-158` |
| 隐含传递依赖 | `miuix-core` / `miuix-squircle` / `miuix-shader`（代码直接用 `squircle*`，未显式声明） | 构建缓存 `top.yukonga.miuix.kmp/*/0.9.4/` |
| 图标集 A | Material Icons **Extended**（`Icons.Rounded.*` 大量使用：Check/Terminal/Close/Language/FolderOpen/Description/Add/Stop/Refresh/Layers/Visibility/Mic/…） | `app/build.gradle.kts:159` |
| 图标集 B | miuix 自带图标（`MiuixIcons.Basic.ArrowRight`、`MiuixIcons.Messages` 等） | `ui/components/EtaPreferenceRow.kt:57`、`ui/components/ConversationPanePanel.kt:200` |
| 自绘矢量 | `ic_atom`（思考强度）、`ic_assistant_send/stop/voice/keyboard`、`ic_kimi_code`、`ic_splash_animated`、`ic_launcher_*` | `app/src/main/res/drawable/` |
| 字体 | 随包 **JetBrains Mono**（Regular + Bold，**无连字版本**），用于终端与代码；正文用系统字体 | `ui/components/EtaFonts.kt:13-16`；`app/src/main/res/font/jetbrains_mono_regular.ttf` / `_bold.ttf` |
| Markdown | `org.intellij.markdown`（IntelliJ 解析器，**不是** markwon/commonmark） | `app/build.gradle.kts:165`；`ui/markdown/MarkdownInline.kt:20-22` |
| 其他 UI 邻近 | `androidx.navigationevent`（预测性返回）、`androidx.lifecycle.*-compose`、`activity-compose`、`kotlinx-serialization-json` | `app/build.gradle.kts:160-183` |
| 非 UI 但影响渲染 | `okhttp` + `okhttp-sse`、`jsoup`、`room`、`datastore-preferences`、`hidden-api-bypass`、`libxposed-service`、`dexkit` | `app/build.gradle.kts:146-183` |

> 待老大定：`EtaFonts` 注释明确说"部分系统会把 `FontFamily.Monospace` 替换成比例字体"，因此终端和代码块**必须**用随包 JetBrains Mono。EV 若走 Web/跨端，这条同样成立（换 `ui-monospace` 系列兜底）。

---

## 5. EV 移植判定

判定口径：**照搬** = 逻辑与参数可直接搬；**改写** = 视觉目标保留但实现路径必须换；**不要** = 依赖 Android/miuix 特性或已被源码自己标注为临时方案；**待老大定** = 设计意图明确但取舍需要产品决策。

| # | 组件 / Token | 判定 | 理由 |
|---|---|---|---|
| 1 | 硬编码颜色（1.1） | **照搬** | 与渲染引擎无关的常量，唯一需要确认的是 `#37393D`/`#E5E7EA` 那对浮层气泡色 |
| 2 | 色彩角色体系（1.3） | **改写** | `onSurfaceVariantSummary` / `dividerLine` / `surfaceContainerHighest` 是 miuix 专名，EV 需自建等价表 |
| 3 | `Spec2025` 色彩规格 + 8 种调色板 | **待老大定** | 是"跟随 HarmonyOS 味"的核心，但 EV 有没有 miuix 决定能不能照搬 |
| 4 | 字号表（1.4） | **照搬** | 纯数值；miuix 那组档位若不自建，需要至少抄 body1/body2/footnote1/footnote2 四档 |
| 5 | 圆角表（1.5） | **照搬** | 但 squircle（超椭圆）**不是** CSS `border-radius` 的圆；EV 需 `corner-shape: squircle` 或 SVG/path 方案，否则观感差一档 → 见 #16 |
| 6 | 间距表（1.6） | **照搬** | 纯数值 |
| 7 | 阴影（1.7） | **照搬** | 只有两处 8dp/8%，照抄即可 |
| 8 | 动效规格（1.8） | **改写** | 弹簧三档（0.6 / MediumLow、0.6 / Medium、NoBouncy / Medium）是核心资产，但 EV 的弹簧实现参数标定不同，需要实测对齐；`Reduce Motion` 必须保留 |
| 9 | 用户消息气泡 | **照搬** | 结构简单、参数完整；squircle 与"右下收角"是关键记忆点 |
| 10 | 助手消息块（无气泡 + 流式 Markdown） | **改写** | Markdown 渲染器是自研的（`MarkdownDocumentBuilder` + `StreamingGfmParser`），搬不了；但"助手不套气泡"这个决定要照搬 |
| 11 | 工具调用折叠块 | **照搬** | 结构（标题+副标题+耗时+箭头+详情）/ 32dp 行 / 10dp 圆角 / 3s 才显示耗时 / 同工具自动分组，全是可读的设计规则 |
| 12 | 输入框玻璃（结构、内距、按钮布局） | **照搬** | 布局与尺寸体系与引擎无关 |
| 13 | 输入框玻璃（`textureBlur` 部分） | **改写** | 依赖 miuix blur 的 `LayerBackdrop` + 运行时着色器；EV 侧需换成 `backdrop-filter` 或等价实现 |
| 14 | 输入框流光（`shimmerPhase` 描边光带） | **照搬**（数学） | 相位/带宽/峰值/周期全是纯数学，只把"绘制阶段读相位"换成 EV 的等效零重绘机制 |
| 15 | 纯黑首页光晕 `homeGlow` | **照搬**（数学） | 同 #14 |
| 16 | squircle 超椭圆形状 | **改写** | 必须换实现；若 EV 只能做圆角矩形，这是整套设计里**最大的观感损失点** |
| 17 | 顶栏（大标题折叠 / 宽屏小标题） | **照搬** | 行为规则清晰（≥600dp 切小标题） |
| 18 | 顶栏磨砂（高斯 vs 渐进两档） | **改写** | 同 #13 |
| 19 | 侧栏（推挤式而非抽屉） | **照搬** | "前台内容被平移"是刻意的空间关系，不要退化成 overlay 抽屉 |
| 20 | 侧栏宽度（84% / 上限 340dp）+ 位移数学 | **照搬** | 纯数值与线性换算 |
| 21 | 设置页三层结构 + 自定义 Layout 对齐 | **照搬** | "动作区最多占剩余 45%"这条约束要一起搬 |
| 22 | 设置页浅色背景覆盖（`#F0F1F2` + `#0080FF`） | **待老大定** | 源码没写为什么设置页要单独改色，像是历史遗留；照搬可能显得和主界面不一套 |
| 23 | 对话框（32dp 圆角 + 双按钮平分） | **照搬** | 参数完整 |
| 24 | 行内代码（只有背景色、无 padding/圆角） | **待老大定** | 在 App 内可接受（行高紧），Web 上通常需要 padding + 4px 圆角，照搬会显得局促 |
| 25 | 代码块（12dp 圆角 + 语言名 + 复制） | **照搬** | 结构标准 |
| 26 | Markdown 语义间距表（`markdownBlockGap`） | **照搬** | 段落 12 / 标题前 22·18 / 标题后 8 / 其他 14，思考态 ×0.75，是可读性调优的成果 |
| 27 | 随包 JetBrains Mono（无连字） | **照搬** | 终端列宽依赖它 |
| 28 | 开关被强制缩放到 44×24dp | **不要** | 用 `layout{}` + `graphicsLayer` 缩放 miuix 原生开关，这属于对库的补丁式适配，EV 直接给原生尺寸控件即可 |
| 29 | `assistantOverlay` 浮层气泡（硬编码 `#37393D`/`#E5E7EA`） | **不要** | 是悬浮助手场景的一次性配色，与主聊天区气泡不是同一套语言 |
| 30 | `EtaPreferenceTheme` 包裹每个对话框 | **待老大定** | 会让对话框背景变成设置页那套浅色覆盖，意图不明 |
| 31 | `SolidWhenMotionReduced = true`（关动画就关玻璃） | **待老大定** | 源码注释自己说"要冻结动效但保留玻璃就改成 false"，这是个可调开关 |
| 32 | 主题色 `onSurfaceVariantSummary @ 75%` / `@ 60%` 这类一次性 alpha | **照搬** | 数值稳定、出现频率高 |

---

## 6. 设计上值得注意的隐含规则（不在需求里，但会影响移植成败）

1. **助手消息没有气泡**，只有用户消息有。整页因此非常"轻"，气泡是稀缺元素（`ui/components/ChatMessageItem.kt:547` vs `:298`）。
2. **用户气泡右下角永远收成 6dp**，是"气泡尾"的语言；编辑态才回四角 20dp（`:390-391`）。
3. **深色下侧栏必须比聊天区亮一档**，否则展开后两区糊在一起 —— 源码专门写了注释（`ui/components/ConversationPanePanel.kt:114-119`）。
4. **所有无限动画都只在"运行中"挂载**，历史条目返回常量，避免长会话里 N 个节点同时跑帧时钟（`ui/components/ChatMessageItem.kt:167-176` 的注释、`:177`）
5. **模糊的底层必须有不透明底色**，否则文字边缘会出现黑边（`ui/components/AgentChatBody.kt:332`、`ui/components/TopBarBackdrop.kt:27-30`）。
6. **首页空态与有消息态用两套玻璃兜底**：空态半透明，有消息态必须实心（`ui/components/AgentChatInputBar.kt:53-60, 140-145`）。这是可读性约束，不是审美选择。
7. **输入框底部磨砂顶部要 `DstIn` 渐隐**，否则出现硬裁切线（`ui/components/AgentChatBody.kt:961-973`）。

---

## 7. 我没看过的 UI 相关目录 / 文件（不假装读全）

整个 `ui/` 目录 **42,283 行 / 176 个 Kotlin 文件**，我按"设计 token + 用户点名的 9 个组件"做了定向阅读，**实际逐行读过约 20 个文件**。以下**没有逐行读**，只看过文件名、目录规模或个别调用点：

| 目录 | 文件数 | 行数 | 我看过什么 |
|---|---|---|---|
| `ui/screens/` | 38 | 11,678 | 只 grep 过 `RoundedCornerShape` / `CharacterPortraitCard` 的 `FavoriteColor`。**终端页（ConsoleScreen、ConsoleGridLine、ConsoleKeyBar、TerminalToolbar、LinuxFilesScreen、SharedFoldersScreen…）、任务页、通知中心、记忆页、备份页、浏览器页、技能页、MCP 页、权限页、系统增强页全部未读** |
| `ui/pages/providers/` | 9 | 3,327 | 只看过圆角 grep 结果（26dp、12dp、10dp 若干）。模型供应商列表/详情/导入表全部未读 |
| `ui/voice/` | 8 | 1,490 | 仅在 `AgentChatInputBar` 里见过 `SpeechDictationButton` / `SpeechInputFeedback` / `rememberSpeechInput` 的调用签名；语音相关界面本身未读 |
| `ui/app/` | 45 | 10,214 | 只读了 `AgentAppTheme` / `EtaMotion` / `AppearanceThemeLocals` / `AgentAppShell` 的 grep 片段 / `AgentAppRoot` 的对话框圆角行。**`StartupSplash`、`TerminalSessionHost`、`ConsoleStore`、`KimiWeb*`、各权限 UI 全部未读** |
| `ui/components/` | 46 | 9,446 | 读了 17 个核心文件。**未读**：`AgentPlanStrip`、`AgentStatusCard`、`AgentWorkSteps`、`AnsiText`、`AgentChatFileAttachments`（只取常量）、`ItemDescriptionDialog`、`LanguagePreference`、`ListEmptyState`、`MemoryRecallRow`、`MiuixScaffoldPage`、`PermissionHealthCard`、`ProviderBranding`、`SectionHeader`、`SlashCommandPicker`、`ToolIcons`、`EtaDropdownPreference`、`EtaFeatureCard`、`EtaSelectionPreferences`、`EtaPreferenceGroupItem`、`TopBarBackdrop` 之外的所有 |
| `ui/markdown/` | 11 | 2,777 | 读了 `MarkdownStyle` 全文、`MarkdownContent` 的代码块/表格段、`MarkdownInline` 样式段。**未读**：`MarkdownDocumentBuilder`、`MarkdownModel`、`StreamingGfmParser`、`SmoothTextReveal`、`StreamingMarkdownState`、`TexMathDelimiters`、`ChatMarkdown` |
| `ui/model/` | 15 | 1,195 | 未读（只用到类型名） |
| `ui/preview/` | 1 | 230 | 未读（`FakeAgentUiStates.kt`，可能是最快的"设计参考样本"，建议 EV 先看这个） |
| `ui/layout/` | 1 | 60 | 只读了两个断点常量 |
| `ui/navigation/` | 2 | 172 | 未读 |
| `ui/AppearanceSettingsScreen.kt` / `SettingsScreen.kt` / `MainActivity.kt` | 3 | 1,694 | 只看过 `SettingsScreen` 的 `EtaPreferenceGroup` 调用点列表。**外观设置页（玻璃滑块的实际 UI）未逐行读** |
| `app/src/main/res/`（布局/主题/字符串） | — | — | 只看过 `res/font/` 与 `res/drawable/` 的文件名。**`res/values/themes.xml`、颜色资源、各语言的 strings 都没看** |

**两处明确的空白，若老大要更完整的清单需要补读**：
1. `ui/screens/terminal/`（13 文件，终端是高密度自定义绘制，`ConsoleGridLine` 按单元格画，是第二套形状/字体体系）
2. `ui/AppearanceSettingsScreen.kt`（419 行）——玻璃与流光的**用户可调面板**长什么样，我只看了生成它的数据模型（`AppearanceSettings.kt`），没看面板本身。

另外：`miuix` 的 **实际色彩值**（light/dark 的 `primary`、`surfaceContainer` 各是什么 RGB）不在仓库里，由 `ThemeController` 在运行时算。上面 1.1/1.2 是仓库里唯一能确定的颜色；要拿到完整亮暗色值表，得在设备上跑一次 `ThemeController` 导出，或者去读 miuix 上游源码——这件事我没做。

---

*本文件为只读提取，未修改任何代码。*
