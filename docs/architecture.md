# EV 架构（四层）

- 事实基线：提交 `8f268b0`（分支 `build/gradle9-agp9`，2026-10-25）。
- 内核处于**冻结状态**，规则见 [kernel-freeze.md](kernel-freeze.md)。
- 本文只写代码与构建文件里能验证的事实。历史计划项一律指向
  [archive/](archive/)，不当作现状。文档导航见 [README.md](README.md)。
- 路径均为仓库相对路径。行号会随改动漂移，检索时优先按符号名搜索。

## 1. 四层与模块

| 层 | Gradle 模块 | 职责 | 关键入口 |
|---|---|---|---|
| 内核 | `core` | Cordis 运行时：`Context`、`Fiber`、`Plugin`、服务/事件/日志 | `core/src/commonMain/kotlin/org/cordis/` |
| 内核 | `loader` | 模块加载与依赖注入树；Android 侧的 dex/APK 插件加载器 | `loader/src/androidMain/kotlin/org/cordis/loader/ModuleLoader.android.kt` |
| 内核 | `hmr` | 热替换服务（`org.cordis.hmr`），平台实现各自提供 | `hmr/src/commonMain/kotlin/org/cordis/hmr/Hmr.kt` |
| 内核 | `include` | 配置层叠/补丁解释器（`composeEntries`、`PatchOptions`） | `include/src/` |
| 内核 | `packages` | `.kplugin` 清单与归档格式 | `packages/src/commonMain/kotlin/org/cordis/packages/PluginPackage.kt` |
| 内核 | `packager` | JVM Gradle 插件，打包 `.kplugin` | `packager/src/` |
| 内核 | `utils` / `timer` / `logger-console` | 内核辅助模块，均 `api(:core)` | 各自 `build.gradle.kts` |
| 宿主 | `:host` | Android 应用进程：扫描、校验、加载、UI 外壳、服务提供 | `host/src/main/kotlin/ev/buan/` |
| 宿主 | `:host-core`（T02） | 仅宿主使用的纯 JVM 逻辑：校验（`ev.buan.hostcore.validation`）、权限元数据、依赖解析；不依赖 Android，也不依赖 `:plugin-api` | `host-core/src/main/kotlin/ev/buan/hostcore/` |
| SDK | `:plugin-api` | 宿主 ↔ 插件业务契约（`Agent*`、`AppearanceService`、`HostNavigation`、`ServiceDirectory`、`HostPermission`、`PermissionService`、`PluginStorage`、`BackgroundService`、`HostPackages`、`PLUGIN_API_VERSION`） | `plugin-api/src/main/kotlin/org/cordis/api/` |
| SDK | `:ui-kit` | 设计系统 `ev.buan.ui.*`，宿主与插件共用 | `ui-kit/src/main/kotlin/ev/buan/ui/` |
| 插件 | `:plugins:*` | 可侧载插件；容器形态与角色见第 6 节 | `plugins/*/src/main/kotlin/` |
| 插件（agent 层） | `:plugins:agent-api` / `agent-model` / `agent-tools` / `agent-memory` / `agent-brain` | 契约（agent-api）+ 模型/工具/记忆能力 + 编排；`eta-ui` 是唯一界面插件（Eta 风格，消费 `AgentService`） | `plugins/agent-*/src/main/kotlin/`、`plugins/eta-ui/` |

模块注册见 [settings.gradle.kts](../settings.gradle.kts)。内核模块由根构建的 `kernelModules` 过滤器统一套上
KMP + Android library 插件；`:host`、`:plugin-api`、`:ui-kit` 与 `:plugins:*` 是 Android-only 模块
（[build.gradle.kts](../build.gradle.kts)）。

`demo/` 是**独立 Gradle 构建**（[demo/settings.gradle.kts](../demo/settings.gradle.kts)），不在根
`settings.gradle.kts` 里，也不是本文四层中的任何一层，见第 7.4 节。

## 2. 真实依赖图

### 2.1 构建期

```
:plugins:*  --compileOnly-->  :loader  --api-->  :core
     |                          (因此插件编译期可见 org.cordis.*)
     +--compileOnly--> :plugin-api --compileOnly--> :core
     |                          \--api--> :ui-kit
     +--compileOnly--> :ui-kit        （`ping-api` 不直接声明，见下方说明）

:host  --implementation--> :core, :loader, :ui-kit, :plugin-api
:host  --implementation--> :host-core                 （T02）

内核内部：loader/utils/timer/logger-console --api--> core
          hmr --api--> core, loader, timer
          include --api--> core, loader
          packager --api--> packages
```

证据：各模块 `build.gradle.kts` 的 `dependencies`；插件模块统一 `compileOnly(project(":loader"))`
并排除运行期 `kotlin-stdlib`（例如 [plugins/probe/build.gradle.kts](../plugins/probe/build.gradle.kts)）。

`ping-api` 不直接声明 `:ui-kit`；经 `:plugin-api` 的 `api` 依赖编译期仍可见 `ev.buan.ui`，
但不打包、不使用。其余插件都直接 `compileOnly(project(":ui-kit"))`。

两条方向性结论：

- 内核**不依赖** `:host`、`:plugin-api`、`:ui-kit`、`:plugins:*`。这是冻结策略能成立的前提。
- `:plugin-api` 只在编译期引用 `:core`（`compileOnly`），运行期 `org.cordis.*` 由宿主提供；
  `:plugin-api` 公开签名里出现内核类型（`ServiceKey`），所以插件编译期仍需要内核在 classpath 上。

### 2.2 运行期类解析

插件容器由 `AndroidModuleLoader` 注册，`HostRuntime.activate` 调用 `modules.register(descriptor)` 与
`modules.import(...)`（[host/src/main/kotlin/ev/buan/HostRuntime.kt](../host/src/main/kotlin/ev/buan/HostRuntime.kt)）。
每个插件一个 `IsolatedPluginClassLoader`（`ModuleLoader.android.kt` 内的 `DexClassLoader` 子类），查找顺序：

1. 共享包 → 宿主 class loader（`DEFAULT_SHARED_PACKAGES` = `java`、`javax`、`android`、`dalvik`、
   `kotlin`、`kotlinx.coroutines`、`org.cordis`，加上 descriptor 里的 `sharedHostPackages`）；
2. 插件自己的 dex；
3. 依赖插件的 class loader（`descriptor.dependencies`）；
4. 平台 class loader 兜底。

`sharedHostPackages` 由宿主固定注入 `HostPackages.shared`
（[plugin-api/src/main/kotlin/org/cordis/api/HostPackages.kt](../plugin-api/src/main/kotlin/org/cordis/api/HostPackages.kt)，
内容必须与 [gradle/shared-packages.txt](../gradle/shared-packages.txt) 的 19 项一致）。

插件可见面因此是：

- **内核 API 完全可见**：`org.cordis.Plugin`、`Context`、`ServiceKey`、`EffectScope`、`Dependencies` 等
  都在共享前缀 `org.cordis` 下。插件入口必须实现 `org.cordis.Plugin`，加载器会强校验
  （`ModuleLoader.android.kt`：`require(instance is Plugin<*>)`）。这是 D2 = A 的直接后果，
  见 [ADR-0002](adr/0002-plugin-interface.md)。
- **SDK 可见**：`org.cordis.api.*` 在 `org.cordis` 前缀内；`ev.buan.ui.*` 显式列入共享清单。
- **宿主实现包不可见**：`ev.buan.*`（`HostRuntime`、`PluginInstaller` 等）不在共享清单里，插件只能通过
  `:plugin-api` 的契约（`AppearanceService`、`HostNavigation`、`ServiceDirectory`）拿宿主能力。

### 2.3 宿主如何把插件接起来

`HostRuntime.start()`（[HostRuntime.kt](../host/src/main/kotlin/ev/buan/HostRuntime.kt)）：

1. 先向 Cordis 根 `Context` 提供 `AppearanceService` / `HostNavigation` / `ServiceDirectory` 三个宿主服务；
2. `PluginInstaller.sync()` 扫描并校验容器；
3. 逐个 `activate()`：`Modules.register` → `Modules.import` → `cordis.plugin(instance, Unit)` →
   `fiber.await()`，带 90 秒启动超时；失败只记录到该插件卡片，不打断其他插件；
4. 启停是活操作：`setEnabled` 直接 `activate` / `deactivate`（`fiber.dispose()` + `modules.release`）。

宿主**没有接线** `hmr`、`include`、`packages`、`packager`：在内核模块里 `:host` 只依赖 `:core` 与
`:loader`（另有 `:ui-kit`、`:plugin-api`、`:host-core`），源码中没有任何 `org.cordis.hmr` 引用。
因此插件文件替换只在**下次进程启动的 sync** 生效，不是热替换。

## 3. 内核冻结策略（D1 = A）

决策与代价见 [ADR-0001](adr/0001-kernel-consumption.md)。落地规则：

- **冻结范围**：`loader`、`core`、`hmr`、`include`、`packages`（`scripts/kernel-freeze.sh` 的
  `KERNEL_PATHS`，整个 `loader` 目录）；T01 的验收命令只比对 `loader/src/androidMain` 与其余四个目录。
- **门禁基线**：提交 `7b13e6b`。`scripts/kernel-freeze.sh` 用 `git diff --name-only` +
  未跟踪文件列表比对基线，出现白名单之外的文件即失败（T01）。
- **白名单**：[gradle/kernel-freeze-allowlist.txt](../gradle/kernel-freeze-allowlist.txt)，目前只允许
  `loader/build.gradle.kts` 与 `loader/src/androidHostTest/**` 两处「构建/测试接线」。
- **发布快照**：内核模块以 `io.github.meteor149:<module>:0.0.1-SNAPSHOT` 发布（根构建
  `publishableModules` 过滤器 + [.github/workflows/publish.yml](../.github/workflows/publish.yml)），
  作为与源码冻结基线对照的已发布版本。

`main..9ad2569` 的内核路径共有三处差异，其中**唯一一处运行时/API 语义改动**是 `c1c73cc` 对
`AndroidModuleVerifier` 签名的修改（`verify(descriptor)` → `verify(id, sha256, descriptor)`，
宿主在 verifier 里拿到哈希）；另有两处构建/测试接线改动：`c1c73cc` 从 `loader/build.gradle.kts`
移除了 `androidInstrumentedTest` 依赖接线，`435f74d` 把 `AndroidModuleDescriptorTest` 从
`loader/src/androidUnitTest/` 迁到 `loader/src/androidHostTest/`。这两处都已登记在
[gradle/kernel-freeze-allowlist.txt](../gradle/kernel-freeze-allowlist.txt)，与上面的冻结范围一致：
脚本按整个 `loader` 目录比对，白名单只放这两个构建/测试接线位置。
T01 把签名改动回退成 `verify(descriptor)`（工作区改动，待本轮审核），宿主改为自己测量哈希
（[host/src/main/kotlin/ev/buan/PluginHash.kt](../host/src/main/kotlin/ev/buan/PluginHash.kt)），
`git diff 7b13e6b -- loader/src/androidMain core hmr include packages` 因此应为空。

## 4. 宿主 11 项职责完成度

> 基线：提交 `9ad2569`；本表为四层架构对照归纳，来源=代码路径 + 用户四层定义。
> 已回填本轮落地的任务（T05/T06/T07/T08/T09/T10/T11）；仍未完成的任务保留基线状态，落地后需更新。
> 图例：✅ 已实现；⚠️ 半成品（有代码，但能力不完整）；❌ 无实现。

| # | 职责 | 状态 | 代码证据（基线 9ad2569） | 本轮目标 |
|---|---|---|---|---|
| 1 | 安装 | ✅ | `host/src/main/kotlin/ev/buan/PluginInstaller.kt`：`sync()` 镜像 `EV/mods` + 内置 assets（`host/build.gradle.kts` 的 `syncDebugPluginAssets` 目前只接 debug 变体），`publish()` 复制进 `files/mods` 并跨复制校验 SHA-256、置只读，`prunePrivate` / `pruneCodeCache` 清陈旧文件 | — |
| 2 | 验签 | ✅ | `host/src/main/kotlin/ev/buan/PluginTrust.kt`：APK 证书 SHA-256 与宿主自身证书集合比对；jar/dex 无 Android 签名，只报 `SigningState.Unsigned` | — |
| 3 | 信任 | ✅ | `host-core/src/main/kotlin/ev/buan/hostcore/validation/PluginValidator.kt` 的签名/身份/加载期门 + `host/src/main/kotlin/ev/buan/UnsignedApprovalStore.kt`（id→sha256，应用私有 SharedPreferences）+ `AndroidModuleVerifier` 加载期复核（文件被换、权限被撤则拒载） | — |
| 4 | 启停 | ✅ | `HostRuntime.setEnabled` / `activate` / `deactivate`：启用即加载、停用即 `fiber.dispose()` + `modules.release`，无需重启。**依赖 hmr 的「插件替换热更新」没有实现**：替换文件只在下次启动 sync 生效 | — |
| 5 | 依赖解析 | ✅ | T10 已落地（基线 `9ad2569` 时还是半成品）：`host-core/src/main/kotlin/ev/buan/hostcore/dependency/DependencyResolver.kt` 做拓扑排序 + 环检测（`DEPENDENCY_CYCLE`，环成员不进接受计划、依赖者报 `DEPENDENCY_MISSING`），`PluginInstaller.acceptOrder` 按依赖序而非文件名接受；APK 可声明 `cordis.dependencies`，jar/dex 的 sidecar 可声明 `dependencies`。`PluginValidator.dependencyGate` 仍要求依赖已在本轮接受。**仍无版本约束**（本轮非目标） | T10（已落地） |
| 6 | 密钥保管 | ✅ | T11 已落地：`:host:releaseGate` = `verifyReleaseSigning` + `verifySharedPackagesInDex` + `verifyForbiddenPackages`；缺 `keystore.properties` 时几秒内失败（`assembleRelease` 被 `mustRunAfter` 排在门禁后，不会先跑一遍 R8）。`-PallowDebugSigning=true` 是唯一显式旁路，且不改变签名结果；单独 `assembleRelease` 仍可产出 debug 签名侧载包，但发布入口一律是 `releaseGate`。运行时信任锚是「当前安装包的证书」（`PluginTrust.ownCertificates`），不是 keystore 文件。详见 [docs/release-signing.md](release-signing.md) | T11（已落地） |
| 7 | 权限仲裁 | ✅ | T05/T06 已落地：`HostPermission` + `PermissionService`（plugin-api）、`PermissionStore` / `PermissionServiceImpl`、管理页授予/撤销、激活期权限门、`verifyAtLoad(requestedPermissions)` 加载期复核；声明来自 APK 的 `cordis.permissions` 或 sidecar 的 `permissions`。**仍是同进程、同 UID 的 API 级门控，不是沙箱**（见 §7.1）。另：`NETWORK` 目前没有宿主中介 API（联网走插件直接调用共享 OkHttp），授权/撤销不改变插件能否联网；真正被门控的是 `PluginStorage`、`BACKGROUND`、`NOTIFICATIONS` 等宿主服务 | T05、T06（已落地） |
| 8 | 后台存活 | ✅ | T09 已落地：plugin-api 提供 `BackgroundService`（`acquire(reason)` / `release()` 引用计数；未获 `BACKGROUND` 授权时 `acquire` 抛 `SecurityException`）；宿主用单进程前台服务 `EvBackgroundService` 承载（`specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE`、通知渠道，Android 14+ 显式 `startForeground` 类型），至少一个插件持引用时保持存活、最后一个引用释放即停止。插件不直接启动 Service；平台仍可拒绝后台启动（抛 `IllegalStateException`，不记引用）；进程被杀后 service 与引用都不保留 | T09（已落地） |
| 9 | UI 外壳 | ✅ | `MainActivity`（应用根 = 插件主页，无主页插件时 `FallbackPage` 仍进得了管理页）、`PluginsActivity`（插件列表/启停/信任）、`SettingsActivity`、`PluginPageActivity`（插件整页渲染）；主题由 `EvTheme` 统一包裹 | — |
| 10 | 崩溃兜底 | ✅ | T08 已落地：`EvApplication` 安装 `Thread.setDefaultUncaughtExceptionHandler`，崩溃时先写 `files/crash/last.txt` + `crash.pending`（`host-core` 的 `CrashMarker` / `CrashReport`），再交回平台默认处理器；下次启动检测到标记即进入**安全模式**（本次不加载任何插件，`HostRuntime.activate` 内另有硬门控兜底），管理页显示崩溃时间与疑似插件并可「退出安全模式」（清标记后重载已启用插件）。**限制**：Compose 没有错误边界，进程内不恢复；疑似插件只是归因提示，不是隔离；安全模式只在下次启动生效 | T08（已落地） |
| 11 | 每个插件的存储 | ✅ | T07 已落地：`PluginStorage` 契约（plugin-api）+ host-core 的路径/配额/原子写/清理纯逻辑（`ev.buan.hostcore.storage`）+ 宿主 `PluginStorageImpl` 按 `files/plugins/<id>/`、`cache/plugins/<id>/` 注入 per-plugin scope；管理页显示占用并可清理，卸载/禁用按策略删除 | T07（已落地） |

## 5. SDK 组成

SDK = `:plugin-api` + `:ui-kit`，都是 `com.android.library`（`minSdk 33`、`compileSdk 37`），
并随根构建的 `publishableModules` 发布（groupId `io.github.meteor149`，version `0.0.1-SNAPSHOT`）。

### 5.1 `:plugin-api`（插件契约）

| 文件 | 内容 |
|---|---|
| `AgentPage.kt` | 整页渲染契约 `AgentPage`（`Page` / `Home`） |
| `AgentSurface.kt` | `PLUGIN_API_VERSION`、宿主自渲染的业务面 `AgentSurface` |
| `AgentUi.kt` | 内联内容契约 `AgentUi.Content` |
| `AppearanceService.kt` | 宿主外观服务 + `ServiceKey` |
| `HostNavigation.kt` | 宿主导航服务 + `ServiceKey` |
| `ServiceDirectory.kt` | 插件间能力目录（可选依赖用）+ `ServiceKey` |
| `HostPackages.kt` | 共享包前缀清单（与 `gradle/shared-packages.txt` 对齐） |
| `HostPermission.kt` | 插件可声明的宿主能力枚举 + wire name 映射（`missing` 规则） |
| `PermissionService.kt` | 插件侧权限查询/断言服务 + `ServiceKey`（宿主为每个插件注入） |

宿主内部校验不在这个模块里：`PluginValidator`、`DexClassScanner`、`HostPolicy`、`PluginCandidate`、
`SigningState`、`ValidationResult`、`PluginRejection` 与错误码已迁到
`:host-core` 的 `ev.buan.hostcore.validation`（T13）。`:host-core` 是纯 JVM，权限字段用 wire name 字符串；
`HostPermission` 留在 `:plugin-api`，由宿主在边界（`PluginInstaller`、`HostRuntime`）做映射。

### 5.2 `:ui-kit`（设计系统）

`ev.buan.ui` 下当前有：`EvAppearance`/`EvThemeMode`/`EvPaletteStyle`/`EvAccentColor`（`EvAppearance.kt`）、
`EvTheme`、`EvPage`、`EvCard`/`EvCardDefaults`、`EvPreferences`（`EvPreferenceGroup`/`EvPreferenceRow`/
`EvPreferenceDivider`/`EvGroupTitle`/`EvPreferenceDefaults`）、`EvSettings`（`EvSettingsSection`/
`EvSettingsRow`/`EvSwitch`…）、`EvInsets`（`evSystemBarsPadding`）、`EvIcons`、`EvGlyphs`、
`EvStatusColors`、`EvBackButton`、`EvDrawer`。

聊天组件（`EvChat`/`EvChatDefaults`/`EvComposer`/`EvComposerDefaults`/`EvHomeHero`/`EvSuggestion` 等）
不属于设计系统，只服务聊天页；由 `eta-ui` 自己携带（`plugins/eta-ui/.../ui/`，
包名 `ev.buan.plugins.eta.ui.ui`）（T12）。
插件私有包**不得**定义 `ev.buan.ui` 下的类：`ev.buan.ui` 是共享包，插件里出现同名类会被
`PluginValidator` 以 `DUPLICATE_PLATFORM_CLASS` 拒绝。

构建上 Compose/Miuix 依赖用 `api` 暴露，`material-icons-core` 用 `implementation`
（宿主只发 icons-core，插件不得引用 extended，见 `gradle/plugin-verify.gradle.kts`）。

### 5.3 共享边界与版本

- 共享包唯一来源是 [gradle/shared-packages.txt](../gradle/shared-packages.txt)（19 项），同时约束
  `HostPackages.shared`、release 的 R8 `-keep`、`:host:verifySharedPackagesInDex`；
  漂移由 `SharedPackagesContractTest` + `:host:releaseGate` 拦截。
- 未列入共享清单的包**不会**回落到宿主（兜底是平台 class loader），清单写错等于插件拿不到该类。
- `PLUGIN_API_VERSION = 1` 只表示当前契约代次，没有向后兼容承诺；插件与宿主必须同代编译。

## 6. 插件三类

### 6.1 按容器形态（三种）

一个插件 = 一个容器 + 一份元数据：

| 形态 | 元数据 | 信任方式 | 例子 |
|---|---|---|---|
| APK | `AndroidManifest` 的 `<meta-data>`：`cordis.entry`、`cordis.api` | 必须与宿主证书一致（`PluginValidator` 签名门） | 无现存样例（`hello` 已摘除构建；宿主 `assets/plugins/android` 的 seed 扫描机制保留） |
| jar | 同名 `.json` sidecar：`id`/`version`/`entryClass`/`apiVersion`（可选 `dependencies`、`permissions`） | 无 Android 签名；默认 `autoTrustUnsigned = true`，首次同步记录 (id, sha256)，字节变化后加载期拒载 | `eta-ui`、`settings`、`agent-*`（另有已摘除构建的实验插件：`probe`、`coldapi`、`ping-*`，代码保留在 `plugins/` 下） |
| dex | 同 jar（容器里是 `classes*.dex`） | 同 jar | 无现存样例 |

容器里必须是 `classes*.dex`；jar 只放 `.class` 会在加载期 `ClassNotFoundException`
（[README.md](../README.md)「插件格式」）。`PluginInstaller.SUPPORTED_EXTENSIONS` 决定扫描
`.apk` / `.jar` / `.dex`。产物由各插件模块的 `bundlePlugin` 任务生成：把 APK 里的 `classes*.dex`
封成 jar，并写 sidecar。

### 6.2 按承担角色（三种，可组合）

| 角色 | 契约 | 例子 |
|---|---|---|
| 界面插件 | `AgentUi`（内联，可组多次）或 `AgentPage`（整页，单次 composition） | `eta-ui`（`AgentPage`，**默认主页**，Eta 风格 UI，消费 `AgentService`）、`settings`；实验插件 `probe`、`coldapi`、`hello`、`ping-ui` 已摘除构建 |
| 服务插件 | 向 `ServiceDirectory.publish` 发布实现，或从中 `lookup` 可选依赖 | `ping-impl`（发布 `PingService`）、`ping-ui`（消费，缺 publisher 时降级）、`agent-model`（发布 `ModelService`）、`agent-tools`（发布 `ToolRegistry`）、`agent-memory`（发布 `MemoryService`）、`agent-brain`（发布 `AgentService`，编排前三个） |
| 契约插件 | 只装接口与 `ServiceKey`，供其他插件经依赖加载器取用 | `ping-api`（`PingService`，空壳 `Plugin`）、`agent-api`（`AgentService`/`ModelService`/`ToolRegistry`/`MemoryService`，空壳 `Plugin`） |

`ping-impl` / `ping-ui` 的 sidecar 声明 `"dependencies": ["ev.buan.plugins.ping.api"]`，
容器里没有 `PingService` 的实现——契约跨容器由依赖加载器解析，服务实例由 `ServiceDirectory` 传递。

agent 能力层是同一规则的实例：`agent-api` 只放契约；`agent-model` / `agent-tools` / `agent-memory`
是能力插件，各自向 `ServiceDirectory` 发布实现，sidecar 声明依赖 `ev.buan.plugins.agent.api`
（model 另声明 `NETWORK`+`PLUGIN_STORAGE`，tools/memory 声明 `PLUGIN_STORAGE`）；`agent-brain` 是编排插件，
每次发送时重新 `lookup` 这三项能力，缺失即降级，sidecar 只依赖 agent-api；`eta-ui` 是唯一界面插件
（Eta 风格 UI，消费 `AgentService`，声明 `PLUGIN_STORAGE` 保存 `sessions.json`）。
它依赖 agent-api，单独安装会被 `DEPENDENCY_MISSING` 拒绝。eta-ui 未获存储授权时
激活闸门拒绝，默认主页落到宿主兜底页。

`agent-tools` 发布的 `ToolRegistry` 同时是**工具总线**：目录里只有这一个注册表，其他能力插件
（agent-shell 即首例）不再自行 publish，而是 lookup 后按插件 id 调 `register(owner, tools)`
挂载工具、停用时 `unregister(owner)`；工具名跨 owner 必须唯一，撞名在注册期抛
`IllegalArgumentException`。贡献工具的插件应在 sidecar 声明依赖
`ev.buan.plugins.agent.tools`，保证总线先于自己激活。

**工具协议**：工具轮走原生 OpenAI tool 协议——`AgentMessage.toolCalls` 承载 assistant 的工具
调用，结果以 `tool` 角色消息按 id 对应回填，两者随会话写入记忆；brain 最多 3 轮、单条结果
截断 8 KB（UTF-16 代理对安全）。供应商响应缺 tool call id 时由 `OpenAiProtocol` 合成
`call_<index>`，请求侧空 arguments 兜底为 `{}`（2026-10-25 起，此前的「user 消息回填」降级
已移除）。记忆存储保持完整追加日志；发给模型的历史视图由 brain 按轮裁剪
（`HistoryTrim`，user 消息为边界、工具组同进同出，上限 20 轮 / 24K 字符）。

## 7. 已知偏差与不承诺

### 7.1 权限不是沙箱

按 D3 = A，权限模型是「声明 + 用户授权 + API 级门控 + 审计」，**不是 OS 级隔离**：

- 插件与宿主同进程、同 UID；插件代码可以直接调用 Android API（文件、反射、系统服务），宿主拦不住。
- **联网没有宿主门控**：`host/src/main/AndroidManifest.xml` 源码没有声明 `INTERNET`，但 merged manifest
  里的 `INTERNET` 由宿主依赖 `com.squareup.okhttp3:okhttp-android:5.5.0` 传递引入（见
  `host/build/outputs/logs/manifest-merger-debug-report.txt`）。宿主实际持有 `INTERNET`；插件与宿主同 UID，
  直接调用宿主共享的 OkHttp 就能联网。`HostPermission.NETWORK` 目前只是声明 + 用户可见授权 + 审计，
  没有对应的宿主网络服务，撤销它不会让插件失去联网能力。
- 权限只能门控**宿主提供的服务**：`PluginStorage`（未在元数据声明时不 provide；声明但未授权时激活闸门拒绝；
  已注入实例的每次 read/write/delete/list 再 `require(PLUGIN_STORAGE)`）与 `BackgroundService`（未获
  `BACKGROUND` 时 `acquire` 抛 `SecurityException`）属于这一类；`NETWORK` 目前不属于这一类。
- 不承诺隔离恶意插件。独立进程/Binder 沙箱是 [PLAN.md](archive/2026-10-24-arch-hardening/PLAN.md)
  §8 的非目标，另立项目。
- 详见 [ADR-0003](adr/0003-permission-scope.md)。T05/T06 已落地「声明 + 用户授权 + API 级门控 +
  加载期复核」（第 4 节第 7 项），但这些门控只覆盖宿主提供的服务；同进程、同 UID 的前提没有变。

### 7.2 插件直接依赖内核 API

D2 = A 的直接代价：插件编译期需要 `:loader`，运行期从宿主拿 `org.cordis.*`，因此**内核 API 对插件完全可见**。
SDK 不能承诺「插件只看到 SDK」；`org.cordis` 从加载器内置共享前缀里拿掉就等于所有插件加载失败。
见 [ADR-0002](adr/0002-plugin-interface.md)。

### 7.3 宿主不使用 hmr

`hmr` 是内核提供的热替换服务，EV 宿主没有接线：在内核模块里 `:host` 只依赖 `:core` + `:loader`，
源码无 `org.cordis.hmr` 引用。启用/停用是进程内的活操作，但**替换插件文件**要等下次启动的 `sync`。
文档与验收都不得把现状写成热更新。

### 7.4 demo 冻结

`demo/` 是独立 Gradle 构建（`demo/settings.gradle.kts`），用 Kotlin `2.1.21` / AGP `8.7.2`
（[demo/build.gradle.kts](../demo/build.gradle.kts)），与主线 Kotlin `2.4.20` / AGP `9.4.1` 失配。
它不在根构建里，不参与主线 CI，本期不升级。

### 7.5 测试与恢复能力的空白

- `:host` 是 AGP 9 application 模块，自身有 `:host:test` / `:host:testDebugUnitTest`（权限、存储、安全模式等
  Android 接线测试）；`./gradlew test` 会按任务名聚合各模块的 `test`（含 `:host:test`、`:plugin-api` 与
  `:ui-kit` 的 `testDebugUnitTest`），CI 也跑这一步。可测的纯逻辑放在 `:host-core`（校验、权限元数据、
  依赖解析、存储、崩溃标记）。
- `loader/src/androidInstrumentedTest` 未接线（需要 `withDeviceTest` + androidx.test + 真机）。
- Compose 没有错误边界，插件 UI 抛异常仍会崩进程；安全模式是**下次启动**的恢复路径，不是进程内隔离。

### 7.6 其他边界

- `PLUGIN_API_VERSION = 1` 不构成兼容承诺；第三方插件分发不可用（API 0.x）。
- release 构建 `-dontobfuscate`：改名会改写插件调用到的方法签名描述符，ABI 直接断裂
  （`host/proguard-rules.pro`）。
- 共享包清单写错不会在编译期报错，只在运行期表现为 `ClassNotFoundException`；
  曾经的 `com.squareup.okhttp3`（Maven group 当包名）就是这样失效的。
- 依赖解析已支持拓扑排序与环检测，APK 与 jar/dex 都能声明依赖（第 4 节第 5 项）；仍无版本约束。
- `:plugins:*` 的 `bundlePlugin` 在任务动作里访问 `Project`（本模块写 sidecar，加上
  `gradle/plugin-verify.gradle.kts` 附加的校验），与 `gradle.properties` 的全局
  `org.gradle.configuration-cache=true` 不兼容。所有插件都会 apply 的
  [gradle/plugin-verify.gradle.kts](../gradle/plugin-verify.gradle.kts) 已集中声明
  `notCompatibleWithConfigurationCache`（单一来源；新插件 apply 该脚本即继承，不要在各模块脚本里重复声明）。
  因此一条命令同时跑全部插件的 `bundlePlugin`（实测 10 个）会 BUILD SUCCESSFUL：存储阶段仍会列出这些任务的
  问题并丢弃该次配置缓存条目（"entry discarded"），但不再以 exit 1 结束。
  [scripts/release-smoke.sh](../scripts/release-smoke.sh) 仍用 `--no-configuration-cache`，让同一批命令里
  其他可缓存的任务保留缓存复用。

## 8. 门禁与验证

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test :plugin-api:testDebugUnitTest :ui-kit:testDebugUnitTest
.\gradlew.bat :host:releaseGate
.\gradlew.bat :plugin-api:apiSnapshotCheck :ui-kit:apiSnapshotCheck   # T14：公开 API 快照
bash scripts/kernel-freeze.sh          # T01；Windows 下用 Git Bash
bash scripts/release-smoke.sh          # 单测 + releaseGate + 插件 jar + 真机清单
```

- `:host:releaseGate` = `verifyReleaseSigning` + `verifySharedPackagesInDex` + `verifyForbiddenPackages`；
  缺 `keystore.properties` 时 `verifyReleaseSigning` 几秒内失败（`assembleRelease` 被排在门禁之后，
  不会先跑 R8），有密钥才进入 release 构建。本地侧载冒烟可加 `-PallowDebugSigning=true` 显式放行，
  它不改变签名结果。不排斥「不带 flag」的配置缓存运行是 T02 的验收项。
- **SDK 公开 API 快照（T14）**：`apiSnapshotCheck` 对比 `api/*.txt`。改了 `:plugin-api` 或 `:ui-kit`
  的公开 API 后，必须跑 `.\gradlew.bat :plugin-api:apiSnapshot :ui-kit:apiSnapshot` 刷新基线并随代码提交，
  否则本地与 CI 的 `apiSnapshotCheck` 都会失败。
- `scripts/kernel-freeze.sh` 需要基线提交 `7b13e6b` 在本地对象库里；浅克隆会直接报错退出，不会静默通过。
- **运行环境**：Windows 下 `scripts/kernel-freeze.sh` 用 **Git Bash** 执行。若用 WSL 的 git，其换行配置
  与 Windows 检出不一致时会把整棵内核树误报为改动（本机实测 89 个文件）——这不是内核被修改，
  换 Git Bash 复跑即可；不要为绕开误报去改脚本或白名单。
- 主线 Android CI 见 [.github/workflows/android.yml](../.github/workflows/android.yml)（T03/T14）：
  kernel-freeze、assembleDebug、test、SDK 单测、SDK API 快照、releaseGate（无密钥，带 `-PallowDebugSigning=true`）。
- **真机验收（2026-10-24）**：PMX110 / Android 17 / arm64 上完成 debug + release 安装、权限授予/撤销、
  probe 15 项、coldapi 8 项、插件整页、安全模式进出、真实模型问答、工具轮、记忆多轮与每插件存储占用核对；
  记录与证据见 [DEVICE-VERIFICATION.md](archive/2026-10-24-arch-hardening/DEVICE-VERIFICATION.md)。
  仍未活体验证：BACKGROUND 前台服务（无插件声明）、真实崩溃 handler、通知权限被拒路径。

## 9. 相关文档

- [ADR-0001 内核消费方式](adr/0001-kernel-consumption.md)（D1 = A）
- [ADR-0002 插件接口](adr/0002-plugin-interface.md)（D2 = A）
- [ADR-0003 权限范围](adr/0003-permission-scope.md)（D3 = A）
- [发布签名](release-signing.md)
- [插件 UI 组合模型](plugin-ui-composition.md)
- [加固计划](archive/2026-10-24-arch-hardening/PLAN.md)
