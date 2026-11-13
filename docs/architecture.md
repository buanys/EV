# EV 架构

> **最后核对：2026-11-06。** 本文只讲结构与边界——四层怎么切、依赖方向、运行期类怎么解析、
> 哪些东西**不承诺**。现状、进度、操作步骤在 [project-overview.md](project-overview.md)，
> 不要再从这里查"现在做到哪了"。
>
> 纪律：只写代码与构建文件里能验证的事实；不写死提交哈希（历史会被重写，硬编码的哈希一旦失效，
> 文档就在无人察觉的情况下变成谎言）；历史审计见 [archive/](archive/)。

## 1. 四层与模块

| 层 | Gradle 模块 | 职责 | 插件可见？ |
|---|---|---|---|
| **内核** | `:core` | Cordis 运行时：`Context`、`Fiber`、`Plugin`、服务 / 事件 / 日志 | ✅ 全部可见 |
| **内核** | `:loader` | 模块加载与依赖注入树；Android 侧 dex/APK 加载器 | ✅ 全部可见 |
| **契约** | `:host-api` | 宿主 ↔ 插件业务契约（`org.cordis.api` + `ev.buan.plugins.agent.api`） | ✅ 全部可见 |
| **契约** | `:ui-kit` | 设计系统 `ev.buan.ui`，宿主与插件共用同一份类 | ✅ 全部可见 |
| **宿主** | `:host-runtime` | 装配、激活、安装、预算、离屏渲染、路由、状态 | ❌ 不可见 |
| **宿主** | `:host-core` | 纯 JVM 逻辑：校验、权限元数据、依赖解析、安装判定、存储布局、崩溃标记 | ❌ 不可见 |
| **宿主** | `:host-app` | Android 入口：Application、4 个 Activity、3 个 Service、启动动画 | ❌ 不可见 |
| **插件** | `:plugins:<类>:<名>` | 17 个可侧载插件，四类见 §6 | — |

内核只含 `core` + `loader`，两者都 vendor 在 `third_party/cordis/` 并被 `KERNEL.lock` 哈希锁定。
`packages`（`.kplugin` 格式）与 `packager`（Gradle 打包插件）在根构建里，但不属于内核四层中的任何一层，
宿主也不依赖它们。

模块注册见 [settings.gradle.kts](../settings.gradle.kts)。内核模块由根构建的 `kernelModules` 过滤器
统一套上 KMP + Android library 插件；`:host-*`、`:ui-kit`、`:plugins:*` 是 Android-only 模块
（[build.gradle.kts](../build.gradle.kts)）。**加新 Android 模块必须改那个过滤器**，否则 KMP 与
android-library 插件会打架。

## 2. 真实依赖图

### 2.1 构建期

```
:plugins:*  --compileOnly--> :loader      （因此插件编译期可见 org.cordis.*）
            --compileOnly--> :host-api
            --compileOnly--> :ui-kit

:host-runtime --implementation--> :core, :loader, :host-api, :host-core
:host-app     --implementation--> :core, :loader, :ui-kit, :host-api, :host-core, :host-runtime
:host-api     --compileOnly--> :core   （公开签名里出现 ServiceKey，运行时由宿主提供）
              --api-->         :ui-kit  （插件编译契约时顺带看见设计系统）
```

两条方向性结论，也是三条门禁里第二条的依据：

- **内核不依赖** `:host-*`、`:ui-kit`、`:plugins:*`。这是冻结能成立的前提。
- **宿主不依赖任何插件**。`:host-app` 里只有"主页槽位"这个概念，不认识 `ev-ui`。

### 2.2 运行期类解析

插件容器由 `AndroidModuleLoader` 注册，`HostActivation.activate` 调 `modules.register(descriptor)`
与 `modules.import(...)`。每个插件一个 `IsolatedPluginClassLoader`（`DexClassLoader` 子类），
`loadClass` 的查找顺序是：

1. **共享包** → 宿主 class loader（`DEFAULT_SHARED_PACKAGES` = `java`、`javax`、`android`、`dalvik`、
   `kotlin`、`kotlinx.coroutines`、`org.cordis`，加上 descriptor 里的 `sharedHostPackages`）；
2. 插件自己的 dex；
3. 依赖插件的 class loader（`descriptor.dependencies`，按 sidecar 声明）；
4. **platform class loader 兜底**——Android 上是 `BootClassLoader`，不含应用类。

`sharedHostPackages` 由宿主固定注入 `HostPackages.shared`，内容必须与
[gradle/shared-packages.txt](../gradle/shared-packages.txt) 的 20 项一致。

第 4 步是**隔离成立的关键**：兜底不是宿主自己的缩小版 class loader，而是平台 loader，
所以 `ev.buan.HostComposition` 这类宿主内部类**根本加载不到**。

插件可见面因此是：

- **内核 API 完全可见**：`org.cordis.Plugin`、`Context`、`ServiceKey`、`EffectScope`。
  插件入口必须实现 `org.cordis.Plugin`，加载器强校验（`require(instance is Plugin<*>)`）。
- **契约可见**：`org.cordis.api.*` 在 `org.cordis` 前缀内；`ev.buan.ui.*` 与 `ev.buan.plugins.agent.api`
  显式列入共享清单。
- **宿主实现不可见**：`ev.buan.*`（`HostComposition`、`PluginInstaller`、`HostActivation`…）不在清单里。

### 2.3 宿主如何把插件接起来

`HostComposition.start()` 分两段：先把宿主服务放上 Cordis 总线，再交给 `HostActivation.sync()`。

**服务发布**（幂等，已提供过的跳过）：`AppearanceService` → `HostNavigation` → `PageReader` →
`ServiceDirectory`（内含主题目录 / 插件目录 / 插件管理 / 页面服务）→ `PluginInstallService` →
`DeviceContextService` / `ScreenService` / `HudService` / `HostRestart`。

**装配与激活**（`HostActivation`）：

1. `PluginInstaller.sync()` 扫描 `EV/mods` 与内置 assets，校验、发布、按依赖图排序；
2. 定目标：安全模式下为空，否则是"启用且落在本次启动范围内"的那些；
3. 逐个 `activate()`：`modules.register` → `modules.import` → 建每插件一颗 Fiber →
   在它上面 `provide` 权限 / 存储 / 预算 / 背景服务 → `pluginContext.plugin(instance)` → `await()`，
   全程 90 秒超时；失败只记到该插件卡片，不打断其他插件；
4. 停用：`fiber.dispose()` + `modules.release(id)`，同时取消它的预算寿命（在飞任务一起收）。

宿主**没有热替换**：内核只 vendor 了 `core` + `loader`，`hmr` 不在其中。启用/停用是进程内的
活操作，但**替换插件文件**要等下次启动的 `sync`。

## 3. 内核冻结

决策与代价见 [ADR-0001](adr/0001-kernel-consumption.md)。落地规则：

- **范围**：`third_party/cordis/` 下的 `core` + `loader` 整个目录树。
- **门禁**：`scripts/verify-kernel-lock.sh` 算目录树哈希，与 `KERNEL.lock` 的 `tree_sha256` 比对。
  算法写在 `KERNEL.lock` 里：对 `core/` 与 `loader/` 下所有文件按相对路径排序，逐行写
  `<sha256>  <path>`，再对整段取 sha256。
- **改内核的唯一方式**：先在上游 `cordis-kotlin` 完成，再同步回来并更新 `KERNEL.lock`（人工审核）。
  宿主需要的功能如果内核做不到，**在宿主里补**，不改内核。
- 上游固定 `7b13e6b`，`KERNEL.lock` 记录 URL、提交、导入日期、许可证。

## 4. 宿主职责

按代码路径核对，11 项里 9 项有实现，两项是空的（见 §7）。

| # | 职责 | 状态 | 证据 |
|---|---|---|---|
| 1 | 安装 | ✅ | `PluginInstaller.sync()`：镜像 `EV/mods` + assets、跨复制校验 SHA-256、置只读 |
| 2 | 验签 | ✅ | `PluginTrust`：APK 证书与宿主自身证书比对；jar/dex 无 Android 签名 |
| 3 | 信任 | ✅ | `PluginValidator` 签名/身份/加载期门 + `UnsignedApprovalStore`（id→sha256）+ 加载期复核 |
| 4 | 启停 | ✅ | `HostActivation.activate/deactivate`；替换文件需重启 |
| 5 | 依赖解析 | ⚠️ | 拓扑排序 + 环检测（`DependencyResolver`）有；**版本约束没有** |
| 6 | 密钥保管 | ✅ | `:host-app:releaseGate` = 签名 + 共享包 + 禁忌包三重校验 |
| 7 | 权限仲裁 | ⚠️ | 声明 + 授权 + 门控 + 加载期复核有；**只覆盖宿主服务**，不是沙箱（§7.1） |
| 8 | 后台存活 | ✅ | `BackgroundService` 引用计数 + 单进程前台服务 |
| 9 | UI 外壳 | ✅ | `MainActivity`（应用根 = 插件主页）、`PluginsActivity`、`SettingsActivity`、`PluginPageActivity` |
| 10 | 崩溃兜底 | ✅ | 崩溃标记 + 下次启动安全模式；进程内不恢复（§7.3） |
| 11 | 每插件存储 | ✅ | `PluginStorage` / `UserDataStorage` + 配额 + 原子写；卸载按策略删 |

**这 11 项之外该有的、现在没有的**：失败计分、版本化回滚（恢复章缺的两项），
以及信任档位——见 §7.2 与 §7.5。

## 5. SDK 组成

`:host-api` + `:ui-kit`，都是 `com.android.library`，随根构建 `publishableModules` 发布。

### 5.1 `:host-api`

| 文件 | 内容 |
|---|---|
| `AgentPage.kt` / `AgentUi.kt` | 页面 / 内联内容两种渲染契约 |
| `PluginManifest.kt` | 插件自述（id、能力、设置项、声明权限）——**"我是什么"的唯一入口**，取代了 2026-11-10 删除的 `AgentSurface` |
| `PluginContract.kt` | 契约版本常量（`PLUGIN_API_VERSION` 整数门 / `HOST_API_VERSION` 范围门） |
| `AppearanceService.kt` / `ThemeService.kt` | 外观与主题目录 |
| `HostNavigation.kt` / `HostPackages.kt` / `HostPermission.kt` | 导航、共享包、能力枚举 |
| `ServiceDirectory.kt` / `PermissionService.kt` | 插件间查表、插件侧权限 |
| `PluginStorage.kt` / `UserDataStorage.kt` | 插件区 / 用户数据区 |
| `ScreenService.kt` / `PageReader.kt` / `DeviceContext.kt` / `HudService.kt` | 平台独占能力 |
| `BackgroundService.kt` / `RuntimeBudgetService.kt` | 后台存活 / 执行预算 |
| `PluginInstall.kt` / `PluginManager.kt` / `PluginManifest.kt` / `PluginPageService.kt` | 插件管理面 |

`ev.buan.plugins.agent.api` 下是 **agent 契约**（25 个文件）：`AgentService`、`ToolRegistry`、
`ModelService`、`MemoryService`、`SessionLog`、`SystemPromptService`、`ApprovalService` 等。
它们是**插件之间的**契约，由宿主代发（走共享包），不是宿主服务。

内部校验不在这个模块里：`PluginValidator`、`DexClassScanner`、`HostPolicy`、`PluginCandidate`、
`SigningState`、`PluginRejection` 与错误码都在 `:host-core` 的 `ev.buan.hostcore.validation`。
`:host-core` 是纯 JVM，权限字段用 wire name 字符串；`HostPermission` 留在 `:host-api`，
由宿主在边界做映射。

### 5.2 `:ui-kit`

`ev.buan.ui` 下有：`EvAppearance`/`EvThemeMode`/`EvPaletteStyle`/`EvAccentColor`、`EvTheme`、`EvPage`、
`EvCard`、`EvPreferences`、`EvSettings`、`EvInsets`、`EvIcons`、`EvGlyphs`、`EvGlyphVectors`（图标语法）、
`EvStatusColors`、`EvBackButton`、`EvMotionTokens`、`EvTypeTokens`、`EvThemeTokens`。

聊天组件（`EvChat`/`EvComposer`/`EvHomeHero`/`EvMarkdown` 等）**不属于设计系统**，只服务聊天页，
由 `ev-ui` 自己携带（`plugins/feature/ev-ui/.../ui/`，包名 `ev.buan.plugins.ev.ui.ui`）。

插件里**不得**定义 `ev.buan.ui` 下的类：那是共享包，出现同名类会被 `PluginValidator` 以
`DUPLICATE_PLATFORM_CLASS` 拒绝。

### 5.3 共享边界与版本

- 唯一来源 [gradle/shared-packages.txt](../gradle/shared-packages.txt)（20 项），同时约束
  `HostPackages.shared`、release 的 R8 `-keep`、`:host-app:verifySharedPackagesInDex`。
  三处必须同步，漂移由 `SharedPackagesContractTest` + `releaseGate` 拦截。
- 未列入清单的包**不会**回落宿主（兜底是平台 class loader），清单写错等于插件拿不到该类。
  曾经的 `com.squareup.okhttp3`（Maven group 当包名）就是这样静默失效的。
- `PLUGIN_API_VERSION = 1` 只表示当前契约代次，没有向后兼容承诺；插件与宿主必须同代编译。

## 6. 插件分类

### 6.1 按容器形态

一个插件 = 一个容器 + 一份元数据：

| 形态 | 元数据 | 信任方式 |
|---|---|---|
| APK | `AndroidManifest` 的 `<meta-data>`：`cordis.entry`、`cordis.api`、`cordis.dependencies`、`cordis.permissions` | 必须与宿主证书一致 |
| jar / dex | 同名 `.json` sidecar：`id`/`version`/`entryClass`/`apiVersion`（可选 `dependencies`、`permissions`） | 无 Android 签名；调试包自动信任，正式包逐个确认（§7.2） |

容器里必须是 `classes*.dex`；jar 只放 `.class` 会在加载期 `ClassNotFoundException`。
产物由各插件模块的 `bundlePlugin` 生成：把 APK 里的 `classes*.dex` 封成 jar 并写 sidecar。

### 6.2 按能力分工（四类，**只约束目录**）

| 目录 | 数量 | 定位 |
|---|---|---|
| `plugins/base/` | 7 | 能力提供者：工具总线、文件、日志、设备、屏幕、shell、Linux 环境 |
| `plugins/feature/` | 5 | 页面与行为：主界面、对话大脑、浏览器、提示词、设置 |
| `plugins/integration/` | 4 | 对接外部：模型服务、网页搜索、forge 编译与安装 |
| `plugins/resource/` | 1 | 数据与主题：默认主题 |

**这条分类不进入机制**：代码里没有任何按类分支的逻辑（没有 `PluginCategory` 这种东西）。
宿主判定一律基于**来源**（签名 / 存储在 assets 还是 mods）与**声明**（权限 / 依赖 / 提供的服务），
不需要、也不应该知道一个插件"属于哪类"。规则里给每类写的差异（基础=随包内置、资源=叶子、
集成=凭据归宿主）目前一条都没落地，它们要么翻译成声明字段，要么放弃——见
[project-overview §8](project-overview.md)。

### 6.3 agent 侧的三条流

| 契约 | 提供者 | 角色 |
|---|---|---|
| `SessionLog` | `agent-log` | **真相源**：会话事件 append-only，历史由它派生（ADR-0006） |
| `MemoryService` | `agent-memory` | **派生物**：蒸馏、笔记、索引；不是历史的权威定义 |
| `ApprovalPolicy` / `ToolGuardRegistry` / `ApprovalService` | 策略插件 / `agent-brain` / `ev-ui` | **控制**：预判 / 硬规则 / 问人，顺序不可换（ADR-0007） |

审计流（全局、跨会话连续）与会话流（按会话分片）共用同一套写入纪律（单写者、seq 严格 +1、
不可改写），但是两个文件——清空一个会话不该在审计流里留下一个洞。

**工具协议**：工具轮走原生 OpenAI tool 协议，`AgentMessage.toolCalls` 承载 assistant 的工具调用，
结果以 `tool` 角色消息按 id 回填，两者随会话写入记忆。一轮的预算默认 **20 次模型往返 /
60 次工具调用 / 5 分钟**，单条工具结果截断 8 K 字符（UTF-16 代理对安全）。
供应商缺 tool call id 时由协议层合成 `call_<index>`。

## 7. 已知偏差与不承诺

### 7.1 权限不是沙箱

按 D3 = A，权限模型是「声明 + 用户授权 + API 级门控 + 审计」，**不是 OS 级隔离**：

- 插件与宿主同进程、同 UID；插件代码可以直接调 Android API（文件、反射、系统服务），宿主拦不住。
- **联网没有宿主门控**：`INTERNET` 是宿主自己持有的（由 okhttp 传递引入）。插件与宿主同 UID，
  直接调共享的 OkHttp 就能联网。`HostPermission.NETWORK` 只是声明 + 用户可见授权 + 审计，
  撤销它不会让插件失去联网能力。
- 权限只能门控**宿主提供的服务**：`PluginStorage`（未声明时不 provide；声明未授权时激活闸门拒绝；
  已注入实例的每次调用再 `require`）、`BackgroundService`（未授权时 `acquire` 抛 `SecurityException`）。
- 不承诺隔离恶意插件。**换一个 UID 这条路也堵死**：跨 UID 就是跨应用，而插件必须由宿主进程
  加载（类身份与 `inject` 绑定都在进程内），`isolatedProcess` 那种真隔离进程又没有任何权限可用。
  论证见 [ADR-0003](adr/0003-permission-scope.md) 与 [ADR-0009](adr/0009-trust-tiers.md)。

### 7.2 信任三档，与它的执行环境

规则要求三档：可信（内置 + 签名）→ 同进程；受限（用户确认的第三方）→ 子进程 + broker；
不可信 → 默认拒绝。**三档都实现了**（[ADR-0009](adr/0009-trust-tiers.md)）：

- **判定**：`TrustGate.decide`（`host-core`，纯函数）按来源 + 签名 + 用户批准过的字节定档，
  输入没有一条来自插件自述；`ValidationResult.Accepted` 带着判定穿过安装期走到卡片。
- **受限的执行环境**：sidecar 声明 `"isolation": "separate-process"` 的插件跑在 `:plugin` 进程，
  宿主只经 Binder 三个动作（装载 / 调用 / 卸载）跟它说话，工具以代理形式挂到宿主的 `ToolRegistry`；
  子进程死掉时 `DeathRecipient` 撤工具 + 记一次失败。
- **不可信**：不执行。未签名未批准的按 `autoTrustUnsigned`（绑 `FLAG_DEBUGGABLE`）处理：
  调试包自动信任，正式包逐个确认（按 id + sha256，字节一变就要重新确认）。

能不能进子进程由 `RestrictedProfile` 判定：**必须由插件声明**（宿主看不出它经 `ServiceDirectory`
查了谁，按权限推断的后果是真机上 12 个插件被误判可隔离后集体失败），再叠加三条机械约束——
只声明 `NETWORK`/`PLUGIN_STORAGE`、无桌面入口、无插件依赖。

**仍是同 UID 的子进程**：它换来故障隔离与生命周期独立，不是权限隔离（同 SELinux 域，照样能 `su`）。

### 7.3 插件直接依赖内核 API

D2 = A 的直接代价：插件编译期需要 `:loader`，运行期从宿主拿 `org.cordis.*`，因此内核 API 对插件
完全可见。SDK 不能承诺"插件只看到 SDK"——把 `org.cordis` 从共享前缀里拿掉，所有插件立刻加载失败。
见 [ADR-0002](adr/0002-plugin-interface.md)。

### 7.4 Compose 没有错误边界

插件 UI 抛异常会崩进程：编译器禁止用 `try/catch` 包 composable。安全模式是**下次启动**的恢复路径，
不是进程内隔离。宿主会记录崩溃（`files/crash/`）并在下次启动不加载任何插件。

### 7.5 恢复只做了两项

| 恢复能力 | 状态 |
|---|---|
| 安全模式 | ✅ |
| 崩溃记录（插件、版本、阶段） | ✅ |
| 失败计分（崩溃/超时累计扣分、超阈值停用） | ❌ |
| 回滚到上一个可用版本 | ❌ |

### 7.6 其他边界

- `PLUGIN_API_VERSION = 1` 不构成兼容承诺；第三方插件分发不可用（API 0.x）。
- release 构建 `-dontobfuscate`：改名会改写插件调用到的方法签名描述符，ABI 直接断裂。
- `bundlePlugin` 与全局 `org.gradle.configuration-cache=true` 不兼容，
  已在 [gradle/plugin-verify.gradle.kts](../gradle/plugin-verify.gradle.kts) 集中声明
  `notCompatibleWithConfigurationCache`（单一来源，新插件 apply 该脚本即继承，不要在各模块里重复声明）。
- **`bundlePlugin` 有时会假绿**：源文件改了仍复用旧 jar，部署前看时间戳或加 `--rerun-tasks`。

## 8. 门禁与验证

```sh
sh scripts/check.sh                # 三条门禁：内核冻结 / 模块边界 / 文件行数
./gradlew test                     # 全部单测
./gradlew assembleDebug            # 宿主 APK
./gradlew :host-api:apiSnapshotCheck :ui-kit:apiSnapshotCheck   # SDK 公开面
sh scripts/release-smoke.sh        # 单测 + releaseGate + 插件 jar
```

- **三条门禁**的判定标准与白名单规则见 [project-overview §6](project-overview.md)。
- **SDK 公开 API 快照**：`apiSnapshotCheck` 对比 `api/*.txt`。改了 `:host-api` 或 `:ui-kit` 的公开
  声明后必须跑 `./gradlew :host-api:apiSnapshot :ui-kit:apiSnapshot` 刷新基线并随代码提交。
  **移动 public 顶层函数所在文件也会改 ABI**（Kotlin 顶层函数的 JVM 类名由文件名决定），
  这条曾让 `theme.default` 插件在真机上激活失败。
- **`:host-app:releaseGate`** = `verifyReleaseSigning` + `verifySharedPackagesInDex` +
  `verifyForbiddenPackages`；缺 `keystore.properties` 时几秒内失败（`assembleRelease` 被排在门禁后，
  不会先跑一遍 R8）。侧载冒烟可加 `-PallowDebugSigning=true` 显式放行，它不改变签名结果。
- **真机验收**（2026-10-24，PMX110 / Android 17）：debug + release 安装、权限授予/撤销、
  插件整页、安全模式进出、真实模型问答、工具轮、记忆多轮与每插件存储占用核对；
  记录见 [DEVICE-VERIFICATION.md](archive/2026-10-24-arch-hardening/DEVICE-VERIFICATION.md)。
  **仍未活体验证**：内存压力、预算打满、卸载删除（见 project-overview §8.6）。

## 9. 相关文档

- [project-overview.md](project-overview.md) — 现状、进度、操作
- [ADR-0001 内核消费](adr/0001-kernel-consumption.md) · [ADR-0002 插件接口](adr/0002-plugin-interface.md) ·
  [ADR-0003 权限范围](adr/0003-permission-scope.md)
- [plugin-ui-composition.md](plugin-ui-composition.md) — 插件 UI 怎么组合进宿主
- [release-signing.md](release-signing.md) — 发布签名
- [newdocs/最高规则/ev-final-rules.md](newdocs/最高规则/ev-final-rules.md) — 验收标准
