# EV 架构加固总计划

- 日期：2026-10-24
- 分支：`build/gradle9-agp9`
- 状态：**已完成（2026-10-24，T01–T31）**；真机验收见 [DEVICE-VERIFICATION.md](DEVICE-VERIFICATION.md)
- 依据：四层架构对照结论（内核边界、宿主 11 项职责、SDK 纯度、插件三类）

## 1. 目标

1. 内核真正「只当依赖用，不改」：回退唯一一处 EV 对内核 API 的修改，并建立冻结门禁。
2. 宿主补齐三项系统层能力：**权限仲裁、每插件存储、崩溃兜底 + 后台存活**。
3. SDK 收敛：`plugin-api` 只留插件契约，`ui-kit` 只留设计系统，宿主内部校验迁到 `:host-core`。
4. 建 agent 层：`agent-api` 契约 + 模型/工具/记忆/大脑能力插件 + 界面插件接入。

## 2. 已定决策（可在批准前改）

- **D1 = A**：内核同仓保留源码 + 冻结门禁 + 发布快照；不拆仓。
- **D2 = A**：承认 Cordis 是插件运行时，插件直接实现 `org.cordis.Plugin`；不新增 `EvPlugin` 适配层，文档写清。
- **D3 = A**：权限 = 声明 + 用户授权 + API 级门控 + 审计，**不是 OS 沙箱**；不承诺隔离恶意插件。
- 另：`demo/` 冻结（Kotlin 2.1.21 / AGP 8.7.2 与主线 2.4.20 / 9.4.1 失配），本期不升级。

## 3. 波次

```
P0 地基        T01 T02 T03 T04
P1 权限        T05 -> T06
P2 存储        T07
P3 可靠性      T08 -> T09
P4 依赖/密钥   T10  T11
P5 SDK 收敛    T12 -> T13 -> T14
P6 agent 层    T15 -> T16/T17 -> T18
```

关键依赖：T05/T10/T11/T12 依赖 P0；T06 依赖 T05；T07 依赖 T06；T08/T09 依赖 T07；
T10 依赖 T05；T13 依赖 T05/T10/T12；T15 依赖 T13；T16/T17 依赖 T15+T06/T07；T18 依赖 T16/T17。

## 4. 任务总表

| ID | 阶段 | 负责人 | 任务 | 依赖 |
|---|---|---|---|---|
| T01 | P0 | kernel-guard | 内核回退 + 冻结门禁 | — |
| T02 | P0 | build-infra | `:host-core` + 配置缓存门禁 | — |
| T03 | P0 | build-infra | release-smoke / Android CI / demo 冻结 | T02 |
| T04 | P0 | sdk-architect | 架构文档 + 3 份 ADR | — |
| T05 | P1 | host-platform | 权限契约与校验器 | T01 T02 |
| T06 | P1 | host-platform | 权限运行时注入与管理页 | T05 |
| T07 | P2 | host-platform | 每插件存储 | T06 |
| T08 | P3 | reliability | 崩溃兜底与安全模式 | T07 |
| T09 | P3 | reliability | 后台存活与 BackgroundService | T08 |
| T10 | P4 | kernel-guard | 依赖拓扑解析 + APK 依赖元数据 | T05 |
| T11 | P4 | build-infra | 发布密钥门禁 | T03 |
| T12 | P5 | sdk-architect | ui-kit 拆分聊天组件 | T02 |
| T13 | P5 | sdk-architect | Validator/DexScanner 迁入 host-core | T05 T10 T12 |
| T14 | P5 | sdk-architect | SDK 公共 API 快照 | T13 |
| T15 | P6 | agent-builder | agent-api 契约插件 | T13 |
| T16 | P6 | agent-builder | agent-model 能力插件 | T15 T06 T07 |
| T17 | P6 | agent-builder | agent-tools + agent-memory | T15 T07 |
| T18 | P6 | agent-builder | agent-brain + frontend 接入 | T16 T17 |

## 5. 任务细则

### T01 内核回退 + 冻结门禁（kernel-guard）
- 回退 `c1c73cc` 对 `loader/src/androidMain/.../ModuleLoader.android.kt` 的 `AndroidModuleVerifier` 签名改动：恢复 `fun verify(descriptor)`、默认 `{ }`、调用点 `verifier.verify(descriptor)`。
- 宿主侧：新增 `host/src/main/kotlin/ev/buan/PluginHash.kt`（把 `androidModuleHash` 从 `PluginInstaller.kt` 抽出）；`HostRuntime` 的 verifier 接收 descriptor，自行计算 `sha256(descriptor.file)` 后调 `verifyAtLoad(id, sha256, ...)`。
- 新增 `scripts/kernel-freeze.sh`：默认基线 `7b13e6b`，对 `loader/src/androidMain core hmr include packages` 做 `git diff --name-only`，出现 allowlist 之外的文件即失败；allowlist 放 `gradle/kernel-freeze-allowlist.txt`（仅 `loader/build.gradle.kts`、`loader/src/androidHostTest/**` 两处构建/测试接线）。
- 验收：`git diff 7b13e6b -- loader/src/androidMain core hmr include packages` 为空；`scripts/kernel-freeze.sh` 通过；`.\gradlew.bat :host:assembleDebug` 通过。

### T02 `:host-core` + 配置缓存门禁（build-infra）
- 新建 `host-core/`：`kotlin("jvm")` 纯 JVM 模块 + JUnit5；`settings.gradle.kts` include `:host-core`；根 `build.gradle.kts` 的 `kernelModules`、`publishableModules` 两个过滤器排除 `:host-core`；`host` 增加 `implementation(project(":host-core"))`。
- 把 `host/build.gradle.kts` 的 `verifySharedPackagesInDex` / `verifyForbiddenPackages` 改成 `buildSrc` 里的独立 Task 类（`@InputFile` + `@TaskAction`，不捕获脚本对象），任务名、分组、文案不变。
- 验收：**不带任何 flag** 的 `.\gradlew.bat :host:releaseGate` 在配置缓存开启下成功，第二次运行命中缓存；`.\gradlew.bat :host-core:test`、`assembleDebug` 通过。

### T03 release-smoke / CI / demo（build-infra）
- `scripts/release-smoke.sh`：`:plugins:chat` → `:plugins:frontend`，jar 列表 `settings probe frontend coldapi`；真机清单第 1/2 条按 `autoTrustUnsigned=true` 现状改写（或注明该开关）。
- 新增 `.github/workflows/android.yml`：ubuntu + JDK21 + `assembleDebug` + `test` + `:plugin-api:testDebugUnitTest` + `:ui-kit:testDebugUnitTest` + `:host:releaseGate` + `scripts/kernel-freeze.sh`。
- `demo/README.md` 顶部加冻结说明。
- 验收：脚本中无 `plugins:chat` 引用；workflow YAML 合法。

### T04 架构文档 + ADR（sdk-architect）
- `docs/architecture.md`：四层职责与真实依赖图（插件 → `org.cordis.*` + SDK）、内核冻结策略、宿主职责完成度表、SDK 组成、插件三类、已知偏差。
- `docs/adr/0001-kernel-consumption.md`、`0002-plugin-interface.md`、`0003-permission-scope.md`：记录 D1/D2/D3 及代价。
- 验收：文档只描述代码事实，引用具体路径，不夸大实现。

### T05 权限契约与校验（host-platform）
- `plugin-api` 新增 `HostPermission` 枚举（`NETWORK`、`PLUGIN_STORAGE`、`BACKGROUND`、`NOTIFICATIONS`、`CLIPBOARD`、`INSTALL_PACKAGES`）。
- `host-core` 新增元数据解析：APK `cordis.permissions`（逗号分隔字符串）/ jar sidecar `"permissions": [...]`。
- `PluginCandidate` 加 `requestedPermissions`，`HostPolicy` 加 `grantedPermissions: Map<String, Set<HostPermission>>`；`PluginValidator` 加权限门 + `PERMISSION_DENIED` 错误码。
- 验收：`host-core` 单测覆盖解析/通过/拒绝；`assembleDebug` 通过。

### T06 权限运行时（host-platform）
- `plugin-api` 新增 `PermissionService`（`isGranted` / `require` + `Key`）。
- 宿主：`PermissionStore`（SharedPreferences `ev.permissions`，id → set）；`HostRuntime.activate` 改为在 **per-plugin 子 Context** 上创建插件，并在该 Context `provide(PermissionService.Key)`。先做 spike 验证子 Context 的 provider 只对后代可见、`Registry` 共享 runtime 行为不变。
- 管理页：显示所需/已授予权限，可授予/撤销；撤销后激活返回 `PERMISSION_DENIED`。
- `plugins/probe` 声明 `PLUGIN_STORAGE` 并显示 `PermissionService` 注入状态（作为活体验证）。
- 验收：单测 + 真机/模拟器手动：撤销权限后 probe 激活失败并显示原因，授权后恢复。

### T07 每插件存储（host-platform）
- `plugin-api` 新增 `PluginStorage`（`read/write/delete/list/atomicWrite`，相对路径），`Key`。
- 宿主实现 `PluginStorageImpl`：根目录 `files/plugins/<id>/`、`cache/plugins/<id>/`；拒绝 `..` 与绝对路径；配额 20MB；临时文件 + rename 原子写。
- 用 T06 的 per-plugin 子 Context 注入；禁用保留数据、卸载/清理删除；管理页显示占用与清理按钮。
- `probe` 增加存储读写行。
- 验收：两个插件同相对路径落到不同目录；`../` 被拒；配额超限报错；卸载后目录删除。

### T08 崩溃兜底与安全模式（reliability）
- `EvApplication` 安装 `Thread.setDefaultUncaughtExceptionHandler`：写 `files/crash/last.txt` + `crash.pending` 标记，再交回默认处理器。
- 下次启动检测到标记：全部插件禁用启动（安全模式），管理页顶部提示 + "退出安全模式"；`HostRuntime` 增加 `lastShownPluginId` 作为疑似插件提示。
- 文档写清：Compose 无错误边界，本期不承诺进程内恢复。
- 验收：手动崩溃 → 重启进安全模式且插件全禁用 → 恢复后正常；单测覆盖标记读写（host-core）。

### T09 后台存活（reliability）
- 新增前台 Service `EvBackgroundService` + 通知渠道；manifest 声明 `POST_NOTIFICATIONS`、`foregroundServiceType`（targetSdk 37，按 Android 14+ 要求处理 `specialUse` 或等效类型 + property）。
- `plugin-api` 新增 `BackgroundService`（`acquire(reason)` / `release()`，引用计数），要求插件已获 `BACKGROUND` 权限，否则抛 `SecurityException`。
- 宿主在中介层实现并注入；不包含任何 agent 语义。
- 验收：acquire 后通知/服务出现，release 后停止；未授权调用抛错。

### T10 依赖拓扑解析（kernel-guard）
- `host-core` 新增 `DependencyResolver`：拓扑排序 + 环检测，单测覆盖。
- `PluginInstaller`：先收集全部候选，再按解析顺序 `accept`，替换 `sortedBy { it.name }`。
- APK 元数据支持 `cordis.dependencies`（JSON 数组字符串）；新增 `DEPENDENCY_CYCLE` 错误码。
- 验收：任意文件名顺序解析正确；循环依赖报明确错误；APK 插件可声明依赖。

### T11 发布密钥门禁（build-infra）
- `releaseGate` 要求存在 `keystore.properties`，否则失败并提示；本地可用 `-PallowDebugSigning=true` 显式绕过。
- 更新 `docs/release-signing.md` 与 CI 说明。
- 验收：无 keystore 时 releaseGate 失败；带 flag 成功；CI 不构建 release（或带 flag）。

### T12 ui-kit 拆分（sdk-architect）
- 把 `EvChat.kt`、`EvComposer.kt`、`EvHomeHero.kt`（含 `EvSuggestion`、`EvComposerDefaults`、`EvChatDefaults`）移到 `plugins/frontend`，**必须换包名**（如 `ev.buan.plugins.frontend.ui`），不得在插件里定义 `ev.buan.ui`（共享包/校验器会拒绝）。
- `ui-kit` 保留 `EvTheme`、`EvCard`、`EvPage`、`EvPreferences`、`EvSettings`、`EvInsets`、`EvIcons`、`EvGlyphs`、`EvStatusColors`、`EvAppearance`。
- 验收：`assembleDebug` + `:plugins:frontend:bundlePlugin` 通过；frontend 不再 import 被移走的类名。

### T13 迁移宿主校验到 host-core（sdk-architect）
- 把 `org.cordis.api.validation.*`（PluginValidator、DexClassScanner、HostPolicy、PluginCandidate、SigningState、ValidationResult、PluginRejection、错误码）整体迁到 `host-core`（包 `ev.buan.hostcore.validation`），测试同步迁移。
- `plugin-api` 只留插件契约：Agent*、AppearanceService、HostNavigation、ServiceDirectory、HostPackages、PLUGIN_API_VERSION。
- 更新 `host` 内所有 import；`SharedPackagesContractTest` 保留在 plugin-api（它校验 `HostPackages` 与共享包清单/proguard）。
- 验收：`plugin-api` 无 validation 包；`host-core` 测试全绿；`assembleDebug` + `releaseGate` 通过。

### T14 SDK API 快照（sdk-architect）
- 为 `plugin-api` + `ui-kit` 增加公开 API 快照（优先 `binary-compatibility-validator`；若与 AGP9/KMP 不兼容，退化为 `javap` 生成签名清单到 `api/`）。
- CI 增加快照 diff 检查；基线入库。
- 验收：快照任务可重复运行且稳定；改 API 未更新快照时 CI 失败。

### T15 agent-api 契约插件（agent-builder）
- 新建 `plugins/agent-api`，包 `ev.buan.plugins.agent.api`；只放接口与数据类：`AgentService`、`AgentMessage`、`AgentEvent`、`AgentReply`、`ModelRef`、`ToolSpec`、`ServiceKey`。
- 打包方式复制 `ping-api` 模式（APK → `bundlePlugin` 抽 dex + json）；manifest `cordis.entry` / `cordis.api`。
- 验收：`assembleDebug` + `:plugins:agent-api:bundlePlugin` 产出 jar/json；无实现代码。

### T16 agent-model 能力插件（agent-builder）
- 新建 `plugins/agent-model`：OpenAI 兼容 chat completions；`NETWORK` 权限；配置（baseUrl/apiKey/model）存 `PluginStorage`；实现 `ModelService` 并通过 `ServiceDirectory` 发布。
- 依赖 `agent-api`；sidecar `dependencies` 指向它；HTTP 用宿主共享的 OkHttp。
- 单测：请求体构造与响应解析（纯函数，不联网）。
- 验收：`assembleDebug` + `bundlePlugin`；无宿主改动。

### T17 agent-tools + agent-memory（agent-builder）
- `plugins/agent-tools`：`Tool`/`ToolRegistry` 契约实现 + 受控文件工具（只能访问 `PluginStorage`），发布到 `ServiceDirectory`。
- `plugins/agent-memory`：`MemoryService` 实现，数据存 `PluginStorage`（JSON 或 Room），发布到 `ServiceDirectory`。
- 均依赖 `agent-api`。
- 验收：`assembleDebug` + `bundlePlugin`；jar 的 `dependencies` 正确。

### T18 agent-brain + frontend 接入（agent-builder）
- `plugins/agent-brain`：实现 `AgentService`，通过 `ServiceDirectory` 查找 `ModelService`/`ToolRegistry`/`MemoryService`，缺失时降级。
- `frontend`：优先查找 `AgentService`，存在则真实收发；不存在保持现有"未接模型"空态且不崩溃。
- 更新 README / 真机清单。
- 验收：全量 `assembleDebug`；关 model 插件不崩；全开可完成一次真实问答；真机清单逐条通过。

## 6. 门禁与审核

每个任务完成必须报告：改动文件、执行的命令与结果、偏差/风险。**禁止 `git commit` / `git reset` / `git checkout`**，改动留在工作区由主理人审核。

主理人审核：
1. `git diff` 看是否越界（只改任务允许路径）；
2. 跑任务声明的验收命令；
3. 检查是否引入产品概念到内核/宿主、是否破坏既有测试；
4. 通过则接受；不通过退回该成员或建返工任务。

全量门禁（P0 后每次合并前）：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test :plugin-api:testDebugUnitTest :ui-kit:testDebugUnitTest
.\gradlew.bat :host:releaseGate
```

## 7. 风险与约定

- **同进程无真沙箱**：权限模型只是 API 门控；文档必须写明，不得宣称安全隔离。
- **并发 Gradle**：多成员可能同时构建；遇到配置缓存/文件锁冲突等待 30 秒重试，不要删 `.gradle`、不要杀 daemon。同一命令不同 `-P` flag 值偶发复用陈旧条目（T11 遇到一次），重跑即恢复，不要据此改配置。
- **插件打包任务的配置缓存**：各插件模块的 `bundlePlugin` 在任务动作里访问 Project，与全局 `configuration-cache=true` 不兼容。只能用 `--no-configuration-cache`，或按 `frontend` 的做法在任务上声明 `notCompatibleWithConfigurationCache`（构建成功但丢弃该次缓存条目，T12 已示范）。新增插件沿用同一处理，不要为此改 `gradle.properties`。
- **demo 冻结**：不在主线 CI；升级另立任务。
- **不改内核**：除 T01 的回退外，任何任务都不得修改 `core/loader/hmr/include/packages`；发现必须改时先报告。
- **不引入新依赖**：除非任务明确要求；优先复用现有共享包。

## 8. 非目标

- 不可信第三方插件沙箱（独立进程/Binder）——另立项目。
- iOS/macOS/桌面端发布。
- demo 升级、聊天 UI 重设计。
- 模型厂商绑定（只做 OpenAI 兼容）。

## 9. 执行结果（收尾）

- T01–T24 全部完成；计划外/收尾任务 T25–T31 也已完成：
  - T25 宿主 `hasStarted` 与首页状态刷新（修复插件整页永久弹回主页）；
  - T26 崩溃兜底文档同步；T27 网络权限口径修正；T28 `HostPermission` KDoc 与实际门控对齐；
  - T29 `ServiceDirectory` 改 `ConcurrentHashMap`（UI 线程 lookup 与激活线程 publish 并发安全）；
  - T30 release-smoke 扩到 9 jar、architecture 补 agent 层；
  - T31 把 `bundlePlugin` 的配置缓存不兼容声明集中到 `gradle/plugin-verify.gradle.kts`。
- 最终门禁：17 个模块 **376 tests / 0 failures**；`:host:releaseGate` 共享包 19 / 禁忌包 8；
  API 快照 143 + 347 行；内核冻结相对 `7b13e6b` 仅 allowlist 构建/测试接线；9 个插件 jar/json 可重建。
- 真机：PMX110 / Android 17 完成 Agent 模型 + 工具 + 记忆端到端、权限/存储、安全模式与 release 包验证，
  见 [DEVICE-VERIFICATION.md](DEVICE-VERIFICATION.md)。
