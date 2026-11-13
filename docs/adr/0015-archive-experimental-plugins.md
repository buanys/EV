# ADR-0015：实验版插件整体归档，正式版从零重写

- 状态：已采纳
- 日期：2026-11-10
- 相关：[ADR-0011 能力面归档](0011-host-capability-catalog-and-version-gate.md)、
  [ADR-0012 删除 AgentSurface](0012-drop-agent-surface.md)

## 背景

2026-11-10 之前，源码树里住着 17 个插件（约 25k 行）：`agent-*` 11 个、`ev-ui`、
`linux`、`forge` / `forge-compiler`、`theme-default`、`_template`。

它们是实验产物，积累了三类债务：

1. **职责混装**：`agent-tools` 一个插件里塞了工具总线、文件工具、管理页（646 行）。
   谁给模型某个能力这件事说不清；停用一个插件会连带拿走一堆不相干的东西。
2. **自造宿主已有的东西**：宿主已经实现了日历、设备控制、浏览器、凭据、存储这些
   按权限下发的服务，插件却各自用 root 或直接调 Android API 绕过去。
3. **契约依赖旧形态**：都实现已经删除的 `AgentSurface`（ADR-0012），
   用 `ev.buan.plugins.*` 命名空间，没有声明 `hostApi` 范围。

把这些东西"修一修"的成本，高于按宿主现有能力重写一遍。

## 决策

**17 个实验版插件整体移出源码树，不做增量重构。**

- 存放位置：`/workspace/EV-archive/2026-11-10-experimental-plugins/`
  （与 2026-11-05 归档内核模块同一套做法：归档区在仓库外，git 历史里仍然可查）
- `settings.gradle.kts` 只保留 `:plugins:base:ev-tools`
- `release.yml` / `release-smoke.sh` 的模块清单与产物核对同步收缩
- `scripts/forge-payload.sh`（只服务于 forge-compiler）一并归档

正式版模块名统一 `ev-` 前缀，插件 id 用 `ev.buan.plugin.*`，从 `ev-tools` 开始重写。

## 代价与限制

- **现在从源码构建的宿主是个空壳。** 39 个服务 Key 里，`agent/*` 那 11 个和
  `host/theme-provider` 没有任何发布者，装上之后没有 agent、没有界面。
  这与"实验版就是废稿"的判断一致，但必须写进文档，否则下次有人构建会发现"什么都没了"。
- **设备上现存的 EV 不受影响**，只要 `/sdcard/EV/mods` 里那批 jar 还在。
  一旦清掉 mods，就必须等正式版骨架写完才能恢复可用状态。
- **`_template` 也归档了。** 新插件直接复制 `plugins/base/ev-tools`——它是按新约定写的
  第一个样板（`hostApi` 范围、`ev.buan.plugin.*` id、壳薄逻辑纯）。
- **git 历史保留**：239 个文件从工作树删除，但历史提交里都在。归档区是给"想直接读"的人看的。
- **旧插件与新插件不能同时部署**：两边抢同一批 `agent/*` 服务 Key，
  而 `ServiceDirectory.publish` 是静默覆盖。这条在骨架写完、一次性替换 mods 之前都成立。

## 后续修正（同日，同一决定的一部分）

ADR 落地的下一步就把范围推到了底：**宿主仓库里一个插件都不留**，包括刚写的 `ev-tools`。

现在的分层：

| 位置 | 内容 | 构建 |
|---|---|---|
| `/workspace/EV` | 宿主（core / loader / host-api / host-runtime / host-app / ui-kit / host-core） | 只构建宿主，没有任何 `:plugins:*` |
| `/workspace/ev-plugins` | 所有正式版插件，一个模块一个插件 | 独立 Gradle 工程，`includeBuild("/workspace/EV")` 取契约 |
| `/workspace/EV-archive/2026-11-10-experimental-plugins/` | 实验版 17 个，只作参考 | 不参与任何构建 |

理由：插件与宿主是两种生命周期的东西——宿主跟着内核冻结与发布门禁走，
插件则要能单独编译、单独分发、频繁重写。放在一个构建里，每次改插件都要拉起整个宿主工程；
分开之后，插件那边 `gradle :ev-tools:bundlePlugin` 就够了。

代价：宿主仓库的 `release.yml` 不再产出插件包，只发 APK；插件另行分发。
