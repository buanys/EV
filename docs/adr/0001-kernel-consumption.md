# ADR-0001：内核消费方式（D1 = A）

- 状态：已采纳
- 日期：2026-10-24
- 决策编号：D1 = A
- 实施任务：[PLAN.md](../archive/2026-10-24-arch-hardening/PLAN.md) T01
- 相关：[architecture.md](../architecture.md) 第 3 节、[ADR-0002](0002-plugin-interface.md)

## 背景

EV 是 Cordis 内核的 Android 宿主。内核指 `loader`、`core`、`hmr`、`include`、`packages`
（以及 `utils`、`timer`、`logger-console`、`packager`），它同时是一个会发布到 Maven Central 的
Kotlin Multiplatform 项目（[settings.gradle.kts](../../settings.gradle.kts)、
[build.gradle.kts](../../build.gradle.kts) 的 `publishableModules`、
[.github/workflows/publish.yml](../../.github/workflows/publish.yml)）。

宿主与内核在本仓库里共存：`:host` 通过 project 依赖直接消费 `:core` 与 `:loader`
（[host/build.gradle.kts](../../host/build.gradle.kts)）。这种同仓共存让「临时改一下内核」非常容易——
提交 `c1c73cc` 就把 `AndroidModuleVerifier.verify(descriptor)` 改成了
`verify(id, sha256, descriptor)`，只为了让宿主在回调里拿到哈希。这是一次真实的边界失守。

需要明确：内核到底是**依赖**，还是**可随手修改的本地源码**。

## 决策

**采用 D1 = A：同仓保留源码 + 冻结门禁 + 发布快照，不拆仓。**

三层含义：

1. **同仓源码**：内核模块继续与 `:host`、`:plugin-api`、`:ui-kit` 在同一个 Gradle 构建里，宿主用
   project 依赖消费，不引入私服、不做跨仓 composite build。
2. **冻结门禁**：`scripts/kernel-freeze.sh` 以 `7b13e6b` 为基线，对 `loader core hmr include packages`
   整个目录做已跟踪 diff + 未跟踪文件检查；出现
   [gradle/kernel-freeze-allowlist.txt](../../gradle/kernel-freeze-allowlist.txt) 之外的文件即失败。
   白名单只放「构建/测试接线」（`loader/build.gradle.kts`、`loader/src/androidHostTest/**`），
   不放运行时语义。
3. **发布快照**：内核模块以 `io.github.meteor149:<module>:0.0.1-SNAPSHOT` 发布，作为与源码基线对照的
   已发布版本；发布流程与主线共用 `.github/workflows/publish.yml`。

配套动作（T01）：把 `c1c73cc` 的签名改动回退成 `verify(descriptor)`，宿主自己测量哈希
（[host/src/main/kotlin/ev/buan/PluginHash.kt](../../host/src/main/kotlin/ev/buan/PluginHash.kt)）。
验收线是 `git diff 7b13e6b -- loader/src/androidMain core hmr include packages` 为空。

## 代价与限制

- **门禁只能证明「没改」，不能证明「不该依赖」**。它检查文件差异，不检查设计：宿主仍然可以直接
  import 内核内部类型（`org.cordis.loader.*` 全部在共享前缀 `org.cordis` 下，插件也看得见）。
  门禁的价值是让每次越界都必须显式登记，而不是自动发现架构错误。
- **构建耦合**。内核与宿主同一次 Gradle 构建、同一份 Kotlin/AGP 版本（Kotlin `2.4.20` / AGP `9.4.1`）。
  升级内核工具链等于升级宿主工具链；`kernelModules` / `publishableModules` 两个过滤器必须随模块增减维护
  （T02 已经因为 `:host-core` 改过一次）。
- **发布与源码可能漂移**。`0.0.1-SNAPSHOT` 不是不可变版本；「发布快照」只是对照基线，
  不构成可复现的供应链保证。
- **同仓改动诱惑仍在**。门禁是事后拦截：改完内核再跑脚本会失败，但失败之前工作区已经是脏的。
  真正守住边界靠评审（[PLAN.md](../archive/2026-10-24-arch-hardening/PLAN.md) §6）与每个任务的路径白名单。
- **不拆仓不解决内核发布节奏**。内核模块仍与宿主在同一个 Gradle 构建里，宿主不消费已发布产物，
  两者只能一起升级。

## 备选方案（未采纳）

- **拆仓 / 独立版本**：内核单独仓库、宿主通过已发布 artifact 消费。边界最硬，但需要双 CI、
  跨仓联调与版本对齐；当前仓库的 `loader` Android 实现与宿主耦合度高（`AndroidModuleLoader`、
  共享包清单、`Plugin` 强校验），拆仓成本大于本轮收益。
- **只用已发布二进制**（不保留源码）：无法本地调试内核、无法跑内核 Android 测试；
  且内核 Android 目标依赖 AGP 9 的 KMP library 插件，二进制消费会把兼容性排障推给宿主。

## 影响

- 任何后续任务不得修改 `core/loader/hmr/include/packages` 的运行时文件；确有必要时先报告、
  在 `gradle/kernel-freeze-allowlist.txt` 登记并说明理由。
- 宿主侧新增能力一律放在 `:host` / `:host-core` / SDK，不往内核加「EV 专属」钩子。
- 该决策的可见后果（插件直接拿到内核 API）与 [ADR-0002](0002-plugin-interface.md) 绑定。
