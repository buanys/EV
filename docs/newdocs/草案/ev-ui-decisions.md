# 设计令牌取值决定记录（C1–C4：以插件内联值为准）

## 0. 决定

- **决定**：冲突 C1–C4（强调色、圆角、间距、字号）的宿主默认主题令牌取值，**以旧插件内联值为准**；旧 ui-kit 令牌值仅作注释保留。
- **决定依据**：用户裁决。时间：2026-11-06（Asia/Shanghai）；原话摘要：「就以插件内为准，先做了再说」。
- **适用范围**：仅限**数据层令牌取值**（`app/src/main/kotlin/buan/ev/theme/EvHostThemeTokens.kt`）与设计资产清单的 C1–C4 条目。
  - 不代表旧设计规范原文已恢复；旧规范 `docs/design/EV-UI设计规范.md` 仍不在归档。
  - 不涉及界面渲染；本次不引入 Compose/cloudy/玻璃效果，不把令牌接入界面。
  - 未被 C1–C4 覆盖的令牌（动效、表面、玻璃、层级、外观等）保持 ui-kit 原值，不因本决定改动。
- **未决项保留**：旧规范原文缺失导致的「设计原则/章节值」仍标「未确认」；内联值多处不一致的取舍见 §2 与各 token KDoc。

## 1. 冲突来源

- 审计：`docs/草案/ev-ui-audit.md`（40 文件版定稿 + ui-kit 附录）。
- 设计资产：`docs/草案/ev-design-assets.md` §6（C1–C5）。C1–C4 原状态为「存在冲突/差异，待人工裁决」，本记录将状态改为「已裁决：以插件内联值为准」。
- 所有数值来源均为 `archive/` 下文件，逐项附 `文件:行号`；路径约定：
  - `archive/ui-kit-legacy/src/main/kotlin/ev/buan/ui/`（旧令牌，记作 `ui-kit/<file>`）
  - `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/`（旧插件内联，记作 `<file>`）

## 2. C1–C4 附表（旧令牌值 → 插件内联值）

### C1 强调色

| 令牌 | 旧令牌值（ui-kit） | 插件内联值（采用） | 内联来源 |
|---|---|---|---|
| `AccentDefault` | `0xFF4C8DFF`（`ui-kit/EvTokens.kt:36`） | **`0xFF5AAEFF`** | `EvComposerArea.kt:331`（`ComposerEdgeBlue`，注释：冷蓝，和 HUD 胶囊边缘光同源） |

- 内联值只出现 1 处，无多值不一致。

### C2 圆角

| 令牌 | 旧令牌值（ui-kit） | 插件内联值（采用） | 内联来源 |
|---|---|---|---|
| `Radii.small` | `8.dp`（`ui-kit/EvTokens.kt:162`） | **`3.dp`** | `ui/EvComposer.kt:412`（`RoundedCornerShape(3.dp)`） |
| `Radii.medium` | `14.dp`（`ui-kit/EvTokens.kt:163`） | **`10.dp`** | `EvWorkProcess.kt:77`（`RoundedCornerShape(10.dp)`） |
| `Radii.large` | `20.dp`（`ui-kit/EvTokens.kt:164`） | **`12.dp`** | `ui/EvModelProvidersScreen.kt:263`（`RoundedCornerShape(12.dp)`） |
| （新增档位）`Radii.xl` | —（旧令牌无此档） | **`14.dp`** | `ui/EvLibraryParts.kt:66`（`RoundedCornerShape(14.dp)`） |
| （新增档位）`Radii.xxl` | —（旧令牌无此档） | **`19.dp`** | `ui/EvModelProvidersScreen.kt:877`（`RoundedCornerShape(19.dp)`） |
| `Radii.fullPercent` | `50` | 保持 `50` | 未被 C2 覆盖（`ui-kit/EvTokens.kt:166`） |
| `Radii.userBubble` | `18.dp` | 保持 `18.dp` | 未被 C2 覆盖（`ui-kit/EvTokens.kt:175-183`） |

- 内联圆角共 **5 个不同值（3/10/12/14/19dp）**，全部保留为独立档位；不沿用旧令牌 8/14/20dp。
- **存在多处不一致，待人工裁决**：见 §3。

### C3 间距

| 令牌 | 旧令牌值（ui-kit） | 插件内联值（采用） | 内联来源（首次出现） |
|---|---|---|---|
| `Spacing.xs` | `4.dp`（`ui-kit/EvTokens.kt:149`） | **`4.dp`** | `EvChatChrome.kt:209` |
| `Spacing.s` | `8.dp`（`:150`） | **`8.dp`** | `EvChatChrome.kt:83` |
| `Spacing.m` | `12.dp`（`:151`） | **`12.dp`** | `EvChatChrome.kt:53` |
| `Spacing.l` | `16.dp`（`:152`） | **`16.dp`** | `EvChatChrome.kt:53` |
| `Spacing.xl` | `20.dp`（`:153`） | **`20.dp`** | `EvChatChrome.kt:120` |
| `Spacing.xxl` | `24.dp`（`:154`） | **`24.dp`** | `ui/EvChat.kt:172` |
| `Spacing.xxxl` | `32.dp`（`:155`） | **`32.dp`** | `EvChatChrome.kt:186` |

- 上述 7 个阶梯值在旧插件内联中均实际出现，数值与旧令牌相同；C3 的差异在于旧插件另有 **37 个非阶梯内联 dp 值**（0、0.33、0.5、0.6、1、2、3、5、6、7、9、10、11、14、17、18、19、26、28、36、38、40、44、52、56、58、84、92、96、110、116、128、132、180、220、280 等），本次**不纳入 Spacing 令牌**（属于组件级尺寸，规范原文缺失，标「未确认」）。
- 因此 C3 令牌数值无变化，但取值依据由「ui-kit 阶梯」改为「插件内联出现值（同值）」并记录来源。

### C4 字号

| 令牌 | 旧令牌字号（ui-kit） | 插件内联字号（采用） | 内联来源 |
|---|---|---|---|
| `Type.display.fontSizeSp` | `28.sp`（`ui-kit/EvTokens.kt:134`） | **`15.sp`** | `ui/EvLibraryParts.kt:82` |
| `Type.headline.fontSizeSp` | `22.sp`（`:135`） | **`15.sp`** | 同上 |
| `Type.title.fontSizeSp` | `18.sp`（`:136`） | **`15.sp`** | 同上 |
| `Type.body.fontSizeSp` | `16.sp`（`:137`） | **`14.sp`** | `ui/EvModelProvidersScreen.kt:893` |
| `Type.label.fontSizeSp` | `14.sp`（`:138`） | **`14.sp`** | 同上 |
| `Type.caption.fontSizeSp` | `13.sp`（`:139`） | **`12.sp`** | `ui/EvLinuxScreen.kt:174` |
| `Type.micro.fontSizeSp` | `11.sp`（`:140`） | **`12.sp`** | 同上 |

- 内联字号只有 **3 个不同值（12/14/15sp）**，按「大→小」映射：15sp 用于 display/headline/title；14sp 用于 body/label；12sp 用于 caption/micro。
- `lineHeightSp`/`fontWeight` **未被 C4 覆盖**，保持 ui-kit 原值（`ui-kit/EvTokens.kt:134-140`），标「未由 C4 覆盖」。
- **存在多处不一致，待人工裁决**：见 §3。

## 3. 多处不一致的内联值清单（不得择一，主值+全量列出）

| 冲突 | 不同内联值 | 全部出现位置 |
|---|---|---|
| C2 圆角 | 3dp、10dp、12dp、14dp、19dp | `ui/EvComposer.kt:412`；`EvWorkProcess.kt:77`；`ui/EvModelProvidersScreen.kt:263`；`ui/EvLibraryParts.kt:66`；`ui/EvModelProvidersScreen.kt:877` |
| C3 间距 | 阶梯内 7 值 + 阶梯外 37 值（见 §2 C3） | 阶梯内：`EvChatChrome.kt:209/83/53/120/186`、`ui/EvChat.kt:172`；阶梯外完整表见 `docs/草案/ev-design-assets.md` §2.1 旧内联表 |
| C4 字号 | 12sp、14sp、15sp | `ui/EvLinuxScreen.kt:174`；`ui/EvModelProvidersScreen.kt:893`；`ui/EvLibraryParts.kt:82` |
| C1 强调色 | 0xFF5AAEFF（采用） vs 0xFF4C8DFF（旧令牌，不采用） | `EvComposerArea.kt:331`；`ui-kit/EvTokens.kt:36` |

**待人工裁决**：C2 的 5 个圆角值、C3 的 37 个非阶梯 dp 值、C4 的 3 个字号在失败语义上如何对应到具体组件（本次只锁定数据层主值映射，未与任何组件绑定）。

## 4. 后续约束

- `EvHostThemeTokens` 中保留旧令牌值注释，格式：`// 旧令牌值 xxx（ui-kit，已由 C1/C2/C3/C4 裁决替代）`。
- 新增测试锁定本附表主值；若未来有人改动令牌值，测试必须失败（见阶段 4 记录）。
- 本决定不改变 `archive/`、`docs/最高规则/`、`docs/规范/`、`third_party/cordis/` 的任何内容。
