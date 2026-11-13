# 旧 ev-ui 设计资产清单（草稿，未接线；已补 ui-kit 令牌值）

- 来源：`archive/ev-ui-legacy/`（旧插件）+ `archive/ui-kit-legacy/`（旧宿主设计系统，后补归档）。
- 路径约定：
  - 令牌来源文件相对 `archive/ui-kit-legacy/src/main/kotlin/ev/buan/ui/`；
  - 旧插件内联值相对 `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/`；
  - `build.gradle.kts`/测试等按归档内实际相对路径。
- 规则：数值原样保留，未换算；用途不确定标「未确认」；同一令牌多值全部列出并在 §6 标「存在冲突，待人工裁决」。
- **重要更新 1**：旧 `ui-kit` 已归档到 `archive/ui-kit-legacy/`，`EvTokens.kt` 等令牌定义可读，本清单已用真实令牌值替换上一版「数值缺失」的占位。旧设计规范 `docs/design/EV-UI设计规范.md` 仍**不在归档中**（仅被注释引用），设计原则原文仍部分未确认。
- **重要更新 2（2026-11-06）**：C1–C4 已裁决为「**以旧插件内联值为准**」，取值依据 `docs/草案/ev-ui-decisions.md`；本清单相应条目已改为内联值，旧令牌值在同一处保留并标注。

## 1. 颜色与表面层级（令牌已确认）

### 1.1 品牌强调色

| 名称 | 数值 | 来源 | 用途 |
|---|---|---|---|
| `AccentDefault`（C1 裁决值） | `Color(0xFF5AAEFF)` | `EvComposerArea.kt:331` | 品牌强调色（Monet 关闭时）；取值依据 `ev-ui-decisions.md` C1 |
| 旧令牌值（不采用） | `Color(0xFF4C8DFF)` | `EvTokens.kt:36` | ui-kit 旧值，仅追溯；已由 C1 裁决替代 |

### 1.2 深色主题颜色令牌（`evDarkColorTokens`，`EvTokens.kt:60-76`）

| 令牌 | 数值 | 行号 | 用途（注释/命名） |
|---|---|---|---|
| `surface0` | `0xFF0E0E10`（pureBlack 时 `Color.Black`） | EvTokens.kt:62 | 页面底色（表面阶梯第 1 级） |
| `surface1` | `0xFF1A1B1E` | EvTokens.kt:63 | 表面阶梯第 2 级 |
| `surface2` | `0xFF232428` | EvTokens.kt:64 | 表面阶梯第 3 级（浮层/玻璃 tint 常引用） |
| `surface3` | `0xFF2E2F34` | EvTokens.kt:65 | 表面阶梯第 4 级（用户气泡） |
| `textPrimary` | `0xFFF2F2F4` | EvTokens.kt:66 | 主文本 |
| `textSecondary` | `0xFFB0B1B8` | EvTokens.kt:67 | 次级文本 |
| `textTertiary` | `0xFF7A7B83` | EvTokens.kt:68 | 三级文本 |
| `accent` | 参数，默认 `EvAccentDefault` | EvTokens.kt:69 | 强调色 |
| `accentSoft` | `accent.copy(alpha = 0.16f)` | EvTokens.kt:70 | 强调色柔和底 |
| `onAccent` | `Color.White` | EvTokens.kt:71 | 强调色上的文字 |
| `success` | `0xFF3ECF8E` | EvTokens.kt:72 | 成功 |
| `warning` | `0xFFF2B84B` | EvTokens.kt:73 | 警告 |
| `danger` | `0xFFF26D6D` | EvTokens.kt:74 | 危险 |
| `dividerGhost` | `Color.White.copy(alpha = 0.04f)` | EvTokens.kt:75 | 分组卡内发丝分隔（注释 :56） |

### 1.3 浅色主题颜色令牌（`evLightColorTokens`，`EvTokens.kt:78-94`）

| 令牌 | 数值 | 行号 |
|---|---|---|
| `surface0` | `0xFFFAFAFB` | EvTokens.kt:80 |
| `surface1` | `0xFFF2F2F5` | EvTokens.kt:81 |
| `surface2` | `0xFFEBEBEF` | EvTokens.kt:82 |
| `surface3` | `0xFFE2E2E7` | EvTokens.kt:83 |
| `textPrimary` | `0xFF1A1B1E` | EvTokens.kt:84 |
| `textSecondary` | `0xFF5A5B63` | EvTokens.kt:85 |
| `textTertiary` | `0xFF9A9BA3` | EvTokens.kt:86 |
| `accentSoft` | `accent.copy(alpha = 0.12f)` | EvTokens.kt:88 |
| `onAccent` | `Color.White` | EvTokens.kt:89 |
| `success` | `0xFF0F9D58` | EvTokens.kt:90 |
| `warning` | `0xFFB26A00` | EvTokens.kt:91 |
| `danger` | `0xFFD93025` | EvTokens.kt:92 |
| `dividerGhost` | `Color.Black.copy(alpha = 0.06f)` | EvTokens.kt:93 |

### 1.4 状态色语义（`EvStatusColors.kt`）

| 状态 | 取值 | 来源 |
|---|---|---|
| 成功 | `EvThemeTokens.colors.success` | `EvStatusColors.kt:16-17` |
| 警告 | `EvThemeTokens.colors.warning` | `EvStatusColors.kt:19-20` |
| 错误 | `EvThemeTokens.colors.danger` | `EvStatusColors.kt:22-23` |
| 运行中 | `MiuixTheme.colorScheme.primary` | `EvStatusColors.kt:25-26` |
| 空闲 | `MiuixTheme.colorScheme.onSurfaceVariantSummary` | `EvStatusColors.kt:28-29` |

### 1.5 强调色种子（Monet，`EvTheme.kt:124-133`）

| 枚举 | 种子色 | 来源 |
|---|---|---|
| `SYSTEM`、`BLUE` | `0xFF3482FF` | EvTheme.kt:125 |
| `PURPLE` | `0xFF6750A4` | EvTheme.kt:126 |
| `PINK` | `0xFFB0006D` | EvTheme.kt:127 |
| `RED` | `0xFFBA1A1A` | EvTheme.kt:128 |
| `ORANGE` | `0xFFB65D00` | EvTheme.kt:129 |
| `YELLOW` | `0xFF7D5700` | EvTheme.kt:130 |
| `GREEN` | `0xFF006D3B` | EvTheme.kt:131 |
| `TEAL` | `0xFF006A6A` | EvTheme.kt:132 |

### 1.6 玻璃与层级参数

| 参数 | 数值 | 来源 | 用途 |
|---|---|---|---|
| `glass.blurRadius` | `24.dp` | `EvThemeSpec.kt:14` | 玻璃背面模糊半径 |
| `glass.tintAlpha` | `0.6f` | `EvThemeSpec.kt:16` | 玻璃 tint 不透明度 |
| `glass.highlightAlpha` | `0.18f` | `EvThemeSpec.kt:18` | 顶边高光强度 |
| `glass.borderAlpha` | `0.12f` | `EvThemeSpec.kt:20` | 玻璃发丝边框 |
| `elevation.cardBorderAlpha` | `0.05f` | `EvSurface.kt:34` | 分组卡发丝边框 |
| `elevation.floatBorderAlpha` | `0.10f` | `EvSurface.kt:36` | 浮层发丝边框 |
| `elevation.floatHighlightAlpha` | `0.06f` | `EvSurface.kt:38` | 浮层顶边高光 |

## 2. 尺寸与排版

### 2.1 字号阶梯（C4 裁决：插件内联字号；lineHeight/fontWeight 未由 C4 覆盖）

| 名称 | fontSize（C4 内联） | 内联来源 | lineHeight（保持 ui-kit） | fontWeight（保持 ui-kit） | 旧令牌字号/来源 |
|---|---|---|---|---|---|
| display | 15.sp | `ui/EvLibraryParts.kt:82` | 36.sp | Medium | 28.sp（`EvTokens.kt:134`） |
| headline | 15.sp | `ui/EvLibraryParts.kt:82` | 30.sp | Medium | 22.sp（`EvTokens.kt:135`） |
| title | 15.sp | `ui/EvLibraryParts.kt:82` | 26.sp | Medium | 18.sp（`EvTokens.kt:136`） |
| body | 14.sp | `ui/EvModelProvidersScreen.kt:893` | 26.sp | Normal | 16.sp（`EvTokens.kt:137`） |
| label | 14.sp | `ui/EvModelProvidersScreen.kt:893` | 20.sp | Medium | 14.sp（`EvTokens.kt:138`） |
| caption | 12.sp | `ui/EvLinuxScreen.kt:174` | 18.sp | Normal | 13.sp（`EvTokens.kt:139`） |
| micro | 12.sp | `ui/EvLinuxScreen.kt:174` | 14.sp | Medium | 11.sp（`EvTokens.kt:140`） |

- 取值依据：`docs/草案/ev-ui-decisions.md` C4。内联字号只有 12/14/15sp 三个值，映射为 15→display/headline/title、14→body/label、12→caption/micro。
- 排版原则「中文正文行高 ≥ 1.6 倍字号」原文仍来自 ui-kit（`EvTokens.kt:114`），但对应字号已由 C4 替代；规范原文缺失，标「未确认」。
- headline 为 2026-10-26 跨屏审计后新增（注释 `EvTokens.kt:118-127`），其字号已被 C4 映射为 15sp。

### 2.2 间距阶梯（`EvSpacingTokens`，`EvTokens.kt:147-156`）

| 令牌 | 数值 | 行号 |
|---|---|---|
| xs | 4.dp | EvTokens.kt:149 |
| s | 8.dp | EvTokens.kt:150 |
| m | 12.dp | EvTokens.kt:151 |
| l | 16.dp | EvTokens.kt:152 |
| xl | 20.dp | EvTokens.kt:153 |
| xxl | 24.dp | EvTokens.kt:154 |
| xxxl | 32.dp | EvTokens.kt:155 |

注释原文（`EvTokens.kt:147`）：「The only legal spacing values, in dp: 4 / 8 / 12 / 16 / 20 / 24 / 32 (spec §3.1).」

- 取值依据：`docs/草案/ev-ui-decisions.md` C3。7 个阶梯值均出现在旧插件内联代码中（数值与旧令牌相同）；内联另有 37 个非阶梯 dp 值（0、0.33、0.5、0.6、1、2、3、5、6、7、9、10、11、14、17、18、19、26、28、36、38、40、44、52、56、58、84、92、96、110、116、128、132、180、220、280 等），属于组件级尺寸，**不纳入 Spacing 令牌**，标「未确认」。

### 2.3 圆角档位（C2 裁决：插件内联圆角；全部 5 个内联值保留）

| 令牌 | 数值（C2 内联） | 内联来源 | 旧令牌值（不采用） |
|---|---|---|---|
| small | 3.dp | `ui/EvComposer.kt:412` | 8.dp（`EvTokens.kt:162`） |
| medium | 10.dp | `EvWorkProcess.kt:77` | 14.dp（`EvTokens.kt:163`） |
| large | 12.dp | `ui/EvModelProvidersScreen.kt:263` | 20.dp（`EvTokens.kt:164`） |
| xl（新增） | 14.dp | `ui/EvLibraryParts.kt:66` | 旧令牌无此档 |
| xxl（新增） | 19.dp | `ui/EvModelProvidersScreen.kt:877` | 旧令牌无此档 |
| full | 50% | `EvTokens.kt:169` | 未被 C2 覆盖，保持 ui-kit |
| userBubble | top 18.dp / bottom 18.dp；`lastInCluster=true` 时 bottomEnd=0.dp | `EvTokens.kt:175-183` | 未被 C2 覆盖，保持 ui-kit |

- 取值依据：`docs/草案/ev-ui-decisions.md` C2；不沿用旧令牌阶梯 8/14/20dp。
- 内联圆角 5 个值（3/10/12/14/19dp）全部保留；其组件语义对应关系规范原文缺失，标「存在多处不一致，待人工裁决」。

### 2.4 外观与密度

| 参数 | 数值 | 来源 | 用途 |
|---|---|---|---|
| `MIN_INTERFACE_SCALE` | 0.8f | `EvAppearance.kt:6` | 界面缩放下限 |
| `MAX_INTERFACE_SCALE` | 1.1f | `EvAppearance.kt:7` | 界面缩放上限 |
| `DEFAULT_INTERFACE_SCALE` | 1f | `EvAppearance.kt:8` | 默认界面缩放 |
| `blurEnabled` 默认 | true | `EvAppearance.kt:23` | 玻璃模糊开关默认 |
| `reducedMotionEnabled` 默认 | false | `EvAppearance.kt:29` | 降低动效默认 |
| 系统栏/刘海内边距 | 系统 systemBars + displayCutout，**不含 IME** | `EvInsets.kt:20-23` | 宿主负责，插件不得猜状态栏高度 |

## 3. 动效参数

### 3.1 弹簧/淡入淡出（`EvMotionSpec`，`EvTokens.kt:200-213`）

| 参数 | 数值 | 来源 | 用途 |
|---|---|---|---|
| `gentleDamping` | 0.8f | `EvTokens.kt:202` | 大表面移动（抽屉/面板） |
| `gentleStiffness` | 300f | `EvTokens.kt:203` | 同上 |
| `snappyDamping` | 0.75f | `EvTokens.kt:205` | 组件级移动（按钮/胶囊/气泡入场） |
| `snappyStiffness` | 600f | `EvTokens.kt:206` | 同上 |
| `bouncyDamping` | 0.6f | `EvTokens.kt:208` | 强调时刻（发送/成功反馈） |
| `bouncyStiffness` | 400f | `EvTokens.kt:209` | 同上 |
| `fadeMillis` | 150 | `EvTokens.kt:211` | 纯 alpha 过渡时长（唯一允许的线性 tween） |
| `FADE_FAST_MILLIS` | 150 | `EvTokens.kt:235` | fadeFast 常量 |

### 3.2 页面转场（`EvPageTransitions`，`EvPageTransition.kt`）

| 参数 | 数值 | 来源 |
|---|---|---|
| `TRAVEL` | 8（宽度的 1/8） | `EvPageTransition.kt:29` |
| 进入/退出的滑动距离 | forward：enter `width/8`、exit `-width/16`；backward 相反 | `EvPageTransition.kt:31-51` |
| scaleIn/scaleOut | 0.985f | `EvPageTransition.kt:35,38,46,49` |
| reduced motion 交叉淡入 | `tween(120)` | `EvPageTransition.kt:32,43` |

### 3.3 按压反馈

| 参数 | 数值 | 来源 |
|---|---|---|
| `pressedScale` | 0.975f | `EvSurface.kt:121` |
| 按压缩放动画 | `EvMotionTokens.snappy()` | `EvSurface.kt:127` |

### 3.4 旧插件内联动效（保留原值，未归一化）

| 项 | 数值 | 来源 |
|---|---|---|
| composer 边缘光周期 | `EDGE_MOVE_MS + EDGE_GAP_MS` = 7500 + 1000 | `EvComposerArea.kt:214,320,323` |
| `EDGE_TRAIL_DP` | 132f | `EvComposerArea.kt:325` |
| `EDGE_SEGMENTS` | 60 | `EvComposerArea.kt:328` |
| 流式光标 | `tween(durationMillis = 500)` | `ui/EvChat.kt:194` |
| 消息进入 | `tween(durationMillis = 600, delayMillis = index * 200)` | `ui/EvChat.kt:363` |

## 4. 设计原则文字（原样引用）

- `EvTokens.kt:24-28`：「EV-UI design tokens, the single source of truth for color / type / spacing / radius / motion. … Design spec: docs/design/EV-UI设计规范.md. Components must read these tokens instead of hardcoding values; anything not expressible as a token is a spec gap, not an invitation to improvise.」
- `EvTokens.kt:38-41`：「Color tokens. The dark theme is a four-step surface ladder (surface0 page background through surface3 user bubble) — grouping and hierarchy come from luminance steps, never from dividers.」
- `EvTokens.kt:114`：「Type scale. Chinese body text keeps line height >= 1.6x font size (spec §2.1).」
- `EvTokens.kt:147`：「The only legal spacing values, in dp: 4 / 8 / 12 / 16 / 20 / 24 / 32 (spec §3.1).」
- `EvTokens.kt:160`：「Radius ladder (spec §3.2): 8 / 14 / 20 plus the full capsule.」
- `EvTokens.kt:190-191`：「The global motion language (spec §6). Every position/size change uses one of these springs; components never invent their own physics. Reduced-motion degrades everything to a snap.」
- `EvThemeSpec.kt:8-11`：「Liquid-glass parameters. Tier 1 is data only: the glass renderer that consumes these values is a later step, so today they document the knobs a theme may turn.」
- `EvSurface.kt:25-31`：「Depth, as three alphas a theme can tune. … The app has no shadows - a dark theme's shadow is just a darker rectangle - so hierarchy is carried by two things instead: a hairline where a surface ends, and a faint top highlight on anything that floats over the page.」
- `EvTheme.kt:16-20`：「Applies the shared design system. The host owns this composition; plugins render inside it and therefore inherit the same Miuix color scheme, typography and density without shipping any theme code of their own.」
- `EvInsets.kt:10-18`：「Keeps content out of the status bar and navigation bar. … The host owns the window, so it also owns this padding - a plugin must never have to guess how tall the status bar is on this device. Deliberately excludes the IME inset…」
- `EvGlyphs.kt:8-15`：「The glyph catalogue. Every mark is drawn in this repository (see EvGlyphVectors.kt) rather than borrowed from the platform icon set…」
- `EvIcons.kt:7-14`：「The only icons plugins can reach. The icon pack is an `implementation` dependency of ui-kit, not `api`…」

## 5. 旧插件对令牌的使用（映射核对）

- `EvThemeTokens.colors.*`：旧 ev-ui 引用 42 次颜色集合解构；实际令牌路径均可由 `EvColorTokens` 提供（surface0–3、textPrimary/Secondary/Tertiary、accent、accentSoft、onAccent、success、warning、danger、dividerGhost），无未定义字段。
- `EvThemeTokens.radii.small/medium/large` = 8/14/20；`smallShape/mediumShape/largeShape/fullShape/userBubbleShape` 均存在（`EvTokens.kt:161-184`）。旧代码 `radii.small`、`radii.medium`、`radii.large` 引用可解析。
- `EvThemeTokens.type.*`：display/headline/title/body/label/caption/micro 齐备（`EvTokens.kt:133-141`）；旧 ev-ui 使用了 caption/body/label/micro/title/display（未使用 headline，headline 为后加）。
- `EvThemeTokens.spacing`：旧 ev-ui 代码未显式使用（inline dp 较多）；新实现应以 spacing 阶梯为准。
- `EvMotionTokens.snappy/bouncy/fadeFast/gentle/reducedAware` 均存在（`EvTokens.kt:237-267`）；旧代码引用可解析。
- `EvThemeTokens.elevation.floatBorderAlpha` = 0.10（`EvSurface.kt:36`），旧代码使用处可解析。
- `EvThemeTokens.reducedMotion`：来自 `EvAppearance.reducedMotionEnabled`（默认 false，`EvAppearance.kt:29`）。

## 6. 冲突/差异清单（C1–C4 已裁决：以插件内联值为准；取值依据 `docs/草案/ev-ui-decisions.md`）

| # | 冲突 | 采用值（插件内联） | 来源 | 状态 |
|---|---|---|---|---|
| C1 | 强调色 | **`0xFF5AAEFF`**（旧令牌 `0xFF4C8DFF` 不采用） | `EvComposerArea.kt:331`；旧值 `EvTokens.kt:36` | **已裁决：内联值** |
| C2 | 圆角 | **3 / 10 / 12 / 14 / 19 dp**（旧令牌 8/14/20 不采用；5 值全部保留） | `ui/EvComposer.kt:412`；`EvWorkProcess.kt:77`；`ui/EvModelProvidersScreen.kt:263`；`ui/EvLibraryParts.kt:66`；`ui/EvModelProvidersScreen.kt:877` | **已裁决：内联值**；组件对应关系仍「存在多处不一致，待人工裁决」 |
| C3 | 间距 | 7 个阶梯值 4/8/12/16/20/24/32 与内联同值；内联 37 个非阶梯 dp 值不纳入令牌 | `EvChatChrome.kt:209`、`:83`、`:53`、`:120`、`:186`；`ui/EvChat.kt:172`；`EvTokens.kt:147` | **已裁决：内联值（阶梯值）；非阶梯值「未确认」** |
| C4 | 字号 | 内联 12/14/15sp：15→display/headline/title，14→body/label，12→caption/micro | `ui/EvLibraryParts.kt:82`；`ui/EvModelProvidersScreen.kt:893`；`ui/EvLinuxScreen.kt:174`；旧阶梯 `EvTokens.kt:133-141` | **已裁决：内联值**；lineHeight/weight 未由 C4 覆盖 |
| C5 | 令牌别名 | `small`/`smallShape`、`medium`/`mediumShape`、`large`/`largeShape` 为同一值的不同表达（8/14/20），非冲突 | `EvTokens.kt:161-168` | 已确认：同一值，非冲突 |

## 7. 统计与未确认

| 项 | 数量/结论 |
|---|---|
| 颜色令牌（深/浅各 14 个字段） | 28 个值（accent 为参数，默认 0xFF4C8DFF；EvTokens.kt:36、EvTokens.kt:60-94） |
| 强调色种子 | 8 组 |
| 状态语义色 | 5 条映射（3 个来自令牌，2 个来自 Miuix） |
| 玻璃/层级参数 | 7 个 |
| 字号阶梯 | 7 级（fontSize+lineHeight+weight） |
| 间距令牌 | 7 个 |
| 圆角令牌 | 3 个 + full + userBubble |
| 动效参数 | 7 个（4 弹簧参数 + fadeMillis + FADE_FAST_MILLIS + reducedAware 语义） |
| 页面转场参数 | TRAVEL=8、scale 0.985、reduced tween 120（EvPageTransition.kt:29-51） |
| 已确认数值冲突（C1–C4） | 4 组（C5 已澄清非冲突） |
| 未确认项 | 见下 |

未确认：
1. 旧设计规范 `docs/design/EV-UI设计规范.md` 原文与章节（§1.3/§1.4/§2.1/§3.1/§3.2/§5.2/§5.5/§6/§6.3/§6.4/§7.1/§7.2/§7.3/§9）不在归档；
2. `StatusRunning`/`StatusIdle` 使用的 Miuix 运行时实际颜色（Token 未固定，跟随主题/Monet）；
3. Miuix `ThemeColorSpec.Spec2025`、`ThemePaletteStyle` 的具体调色参数（在 miuix 库内部，未展开）；
4. 旧内联 dp/sp/圆角值是否为组件专属允许值（C2–C4 待规范裁决）；
5. 玻璃渲染器实际实现（Tier 1 仅参数，`EvThemeSpec.kt:9-10` 自述）；
6. 图标矢量（`EvGlyphVectors.kt` 455 行为绘制数据，本清单未逐路径提取）。

## 8. 抽查记录（写入时已对照原文）

| 文件:行 | 值 | 结果 |
|---|---|---|
| EvTokens.kt:36 | `0xFF4C8DFF` | 已核对 |
| EvTokens.kt:62-65 | `0xFF0E0E10` / `0xFF1A1B1E` / `0xFF232428` / `0xFF2E2F34` | 已核对 |
| EvTokens.kt:72-74 | `0xFF3ECF8E` / `0xFFF2B84B` / `0xFFF26D6D` | 已核对 |
| EvTokens.kt:80-83 | `0xFFFAFAFB` / `0xFFF2F2F5` / `0xFFEBEBEF` / `0xFFE2E2E7` | 已核对 |
| EvTokens.kt:134-140 | 28/22/18/16/14/13/11 sp（行高 36/30/26/26/20/18/14） | 已核对 |
| EvTokens.kt:149-155 | 4/8/12/16/20/24/32 dp | 已核对 |
| EvTokens.kt:162-164 | 8/14/20 dp | 已核对 |
| EvTokens.kt:175-183 | userBubble top 18/bottom 18，bottomEnd 0/18 | 已核对 |
| EvTokens.kt:202-211 | 0.8/300、0.75/600、0.6/400、150ms | 已核对 |
| EvThemeSpec.kt:14-20 | 24dp、0.6f、0.18f、0.12f | 已核对 |
| EvSurface.kt:34-38 | 0.05f、0.10f、0.06f | 已核对 |
| EvPageTransition.kt:29 | TRAVEL = 8 | 已核对 |
| EvPageTransition.kt:35 | scale 0.985f | 已核对 |
| EvAppearance.kt:6-8 | 0.8f/1.1f/1f | 已核对 |
| EvTheme.kt:125-132 | 8 个强调色种子 | 已核对 |
| EvComposerArea.kt:331 | `0xFF5AAEFF` | 已核对（冲突 C1） |
| ui/EvChat.kt:194 | `tween(durationMillis = 500)` | 已核对 |
| ui/EvChat.kt:363 | `tween(600, delay = index*200)` | 已核对 |
