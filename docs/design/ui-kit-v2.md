# ui-kit v2：重来一遍的设计约束

- 日期：2026-11-10
- 状态：设计中（旧实现已归档，`ui-kit/src` 已清空）
- 旧版：`/workspace/EV-archive/2026-11-10-ui-kit/ui-kit-before-redesign.tar.gz`（29 个文件）

## 为什么要重来

旧版被否掉不是因为长得丑，是因为**结构上就有第二份真相**：

1. 形状写死在 `object EvShapes { card = RoundedCornerShape(20.dp) }`，
   而 spec 里另有 `radii` 已经进了 CompositionLocal。换主题时圆角不跟着变。
2. 图标集 token 传下来没人读，`EvIcons` 硬绑 `CouiIcons`。
3. `glass` / `elevation` 两个 token 零消费者——"预留"变成了"空着"。

三条是同一类错误：**同一件事有两个来源**。这种东西不能靠补丁修，补丁只会把
"两个来源"复制到每个组件。所以整体推倒。

## 五条硬约束

### 1. 单一来源

任何视觉值都必须能追溯到一个地方：

```
EvThemeSpec  →  token（颜色/字号/形状/间距/玻璃/高度/图标）
             →  CompositionLocal
             →  组件
```

**组件里不许出现字面量 dp / 颜色 / 字号**。唯一的例外是几何尺寸（高度、宽度），
而且必须集中在 `EvMetrics` 里说明理由。

### 2. COUI 是唯一实现，不许自绘

上一版最大的错：用 `Box + background(shape)` 画了个"像卡片"的东西。
COUI 的材质、轮廓光、按压形变都在**组件内部**，手写的盒子一个都拿不到。

- 列表/分组 → `Group` / `Item`
- 弹窗 → `Dialog`
- 悬浮按钮 → `Fab`（**必须传同一个 backdrop**，见约束 4）
- 文字 → `Text(kind = CouiTextKind.…)`

### 3. 两套刻度，一个源头

COUI 组件读 `CouiColorScheme` / `CouiTypography`；EV 组件读 `EvColors` / `EvTypeTokens`。
它们**都从同一份 spec 算出来**，但互不覆盖：EV 的七级字阶不塞进 COUI 的三个槽位。
"总主题"的含义就是这个。

### 4. backdrop 只有一个实例

`Fab` 的崩溃（`FAB 需要显式 backdrop`）本质是：它采样的 backdrop 和页面绘制的不是同一个。
所以页面容器与所有悬浮件**共用一个 `rememberCouiBackdrop()`**，由页面级 composable 持有并下发。

### 5. 组件不做全局查询

组件只从 CompositionLocal 与参数取东西，不查服务、不读文件、不碰单例。
这样每个组件都能独立预览、独立测试。

## 模块结构

```
ui-kit/src/main/kotlin/ev/buan/ui/
  token/      EvThemeSpec、EvColors、EvType、EvRadii、EvSpacing、EvGlass、EvElevation、EvIcons（类型）
  theme/      EvTheme（唯一组合入口）、EvThemeRegistry、EvShapes（由 spec 派生）
  component/  Page、Card、Group、Row、EmptyState、Composer、Collapsible、FloatingActions、SidePage、Dialog
  icon/       EvIcons（读 LocalEvIconSet，未提供时回落 CouiIcons）
```

## 组件清单（先做页面真正用到的）

| 组件 | 层 | 说明 |
|---|---|---|
| `EvPage` | 容器 | 无顶部栏版本；提供 backdrop、系统栏内边距、内容滚动 |
| `EvCard` | 面 | 一块内容面；颜色/形状来自 token |
| `EvPreferenceGroup` / `EvPreferenceRow` | 列表 | 设置、插件页的行与组 |
| `EvEmptyState` | 状态 | 空列表时那一行话（不是提示卡片） |
| `EvComposer` | 输入 | 固定 4 行：2.5 行文本 + 第 4 行工具栏 |
| `EvCollapsible` | 列表 | 工具调用的折叠块 |
| `EvFloatingActions` | 悬浮 | 更多 / 加号，共享页面 backdrop |
| `EvSidePage` | 覆盖层 | 侧面页 |
| `EvApprovalDialog` | 覆盖层 | 基于 COUI `Dialog` |

## 落地顺序

1. `token/` + `theme/`（先让"单一来源"成立）
2. `component/EvPage` + `EvCard` + `EvPreferenceGroup/Row` + `EmptyState`
3. host-app 的七个界面改用新组件
4. 输入区 / 折叠块 / 悬浮件 / 侧面页 / 审批弹窗
5. 删 Miuix 依赖与 `HostPackages` 里的包名

## 已知的连锁反应

`ui-kit/src` 清空之后，`:host-api` 与 `:host-app` 会编译失败——它们引用的
`ev.buan.ui.*` 暂时不存在。这是**预期的红**，不是坏掉：先立地基，再改调用方。
在新地基落地前不要出包。

## 决定：走组合式（A）

2026-11-10 定。不做 `PageSpec` 声明式渲染器。

理由：声明式只有两种结局——要么对话页做不出来，要么为它发明一门 DSL
（`MessageListSpec`、`ComposerSpec`、`StreamingTextSpec`…），之后每加一个特殊页面
就扩展一次 DSL。7 个页面里有 2 个是"特殊页面"，比例太高。

因此：

- 页面直接调用框架组件（`EvSection` / `EvRow` / `EvText` …）
- **视觉 100% 由框架消化**：颜色、字号、形状、材质、按压、过渡都不出现在页面里
- 靠**构建期门禁**保证这条：页面文件出现 `Color(` / `.dp` / `.sp` /
  `import com.itos.coui` / `import androidx.compose.material3` → 构建失败

门禁比约定重要：约定三个月后一定会被打破，门禁不会。

## 进度

- [x] 数据层：`EvTokens` / `EvAppearance` / `EvThemeSpec` / `EvThemeRegistry` / `EvInsets` /
      `EvSpecs`（`EvIconSet`、`EvElevationSpec`）/ `EvTheme`
- [ ] 组件层（20 个，见上表）
- [ ] host-app 七屏改造
- [ ] 删 Miuix 与 `HostPackages` 里的包名
- [ ] 页面门禁脚本
