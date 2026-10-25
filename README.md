# Cordis Kotlin

A Kotlin implementation of [Cordis](https://github.com/cordiverse/cordis).

> The project is under active development. APIs may change without notice.

## Install

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("io.github.meteor149:loader:0.0.1-SNAPSHOT")
}
```

Plugin APKs should compile against the API without packaging it:

```kotlin
compileOnly("io.github.meteor149:loader:0.0.1-SNAPSHOT")
```

## Platforms

Currently supported:

- Desktop JVM
- Android (minSdk 26)

## Desktop JVM dynamic loading

The optional [packages](packages/README.md) module supplies cross-platform `.kplugin`
manifests, runtime-neutral system/architecture/bitness/version-range variant selection, exact package dependency resolution,
Linux distribution and system feature constraints, and verified offline archive tooling.
Application SDK compatibility, publisher
trust, configuration and installation transactions remain the host's responsibility.

The optional [packager](packager/README.md) Gradle plugin (`io.github.meteor149.cordis.packager`) lets each
KMP module declare independent releases. It prepares JVM JARs and Android plugin APKs from
existing compilation outputs, builds `.kplugin` archives, and exposes them as Gradle artifacts
for product bundles to collect. Android libraries do not need separate application wrapper
projects. Application SDK metadata and default composition remain consumer-owned.

The [Include composer](include/README.md) supplies detached layered patches for configuration
tooling and runtime loading, including positioned insertion and parent/order changes. Loader
tree transactions recreate changed-parent branches in their new context and retain resources
for same-parent ordering. Named profiles, package trust and durable generations belong to hosts.

The desktop loader runs Cordis plugins from verified JARs in an application-controlled directory.

- Plugin code, resources, private JAR dependencies, and JNI libraries are isolated per generation.
- JDK, Kotlin, Cordis APIs, and explicitly allowed host API packages preserve host type identity.
- Plugin module dependencies form an explicit class-loader graph; arbitrary host implementation
  packages are not visible.
- Plugin releases can be replaced transactionally through the HMR service. Failed imports or
  plugin activation roll back to the previous running generation.
- Superseded and rolled-back class loaders are closed when active dependents no longer retain them.

The host remains responsible for downloading releases and establishing trust before registration.
See [loader/README.md](loader/README.md#desktop-jvm-plugin-jars) for setup, packaging, and lifecycle
details.

## Android dynamic loading

Explicit Fiber withdrawal cancels a suspended plugin `apply` operation and waits for its
collected resource cleanup. Cleanup runs in the transition owner rather than the cancelled
allocation job. Dependency relocation retains its existing wait-for-load behavior. Plugins
should allocate resources during `apply`, collect every disposer, and keep allocation
cancellable; blocking platform calls still require their own cancellation strategy.

Fiber retirement attempts every collected disposer and propagates cleanup failures to
`dispose`, `restart`, `update` and subsequent `await` calls. Failed cleanup prevents automatic
reactivation, including when a missing dependency returns. Explicit disposal still reaches
Disposed and repeated disposal reports the retained failure without rerunning releases.
Allocation failures retain their original cause, with partial-cleanup errors suppressed.
Hosts must treat uncertain retirement as a recovery boundary rather than allocate an overlapping
replacement. A failed disposer is not automatically retried; recovery may require restarting
the process or an application-specific resource recovery mechanism.

The Android loader runs Cordis plugins from APK, JAR, or dex files in the app's private storage.

- Plugin implementation classes use an isolated, child-first class loader.
- Android, Kotlin, coroutines, Cordis APIs, and explicitly allowed host APIs are shared by the host.
- Each plugin gets its own resources, assets, class loader, and package context.
- Plugin Activities and Services run through non-exported host proxy components provided by the AAR.
- Plugin releases can be replaced transactionally; a failed reload keeps the previous generation active.

The loader does not download plugin files or establish trust. The host must download, verify, and store them securely before registration. See [loader/README.md](loader/README.md) for integration details and limitations.

## Compose Multiplatform plugin lab

The [demo](demo/README.md) is a shared Compose Multiplatform application for Desktop JVM and
Android. It builds isolated plugin JARs/APKs, exercises declared dependencies and private
classpaths, performs transactional hot replacement, proves failed-release rollback, and exposes
Android proxy Activity routing from the same host UI.

Licensed under [MIT](LICENSE).

---

# EV（Android 宿主与插件平台）

本仓库除内核外还带一个可侧载的 Android 宿主 `:host`（应用名 EV）、设计系统 `:ui-kit`、
契约模块 `:plugin-api` 与若干插件模块 `:plugins:*`。**内核（core/loader/…）的语义未改动**，
EV 只在其上增加宿主策略。

> **内核已冻结（2026-10-25 起）。** 内核目录（loader/core/hmr/include/packages）只当依赖用，
> 不接受业务改动，由 `scripts/kernel-freeze.sh` 门禁强制。规则与例外流程见
> [docs/kernel-freeze.md](docs/kernel-freeze.md)。全部文档导航见 [docs/README.md](docs/README.md)。

> **API 版本 0.x，未稳定。** `PLUGIN_API_VERSION = 1` 只表示当前契约代次，
> 不表示向后兼容承诺；插件与宿主必须同代编译。不要基于它做第三方分发。

## 插件格式

一个插件 = **一个容器 + 一份元数据**：

| 形态 | 元数据位置 | 字段 |
|---|---|---|
| APK | AndroidManifest 的 `<meta-data>` | `cordis.entry`（入口类全名）、`cordis.api`（契约版本）、`cordis.dependencies`（JSON 数组字符串，如 `["ev.buan.plugins.ping.api"]`）、`cordis.permissions`（逗号分隔字符串，如 `NETWORK,PLUGIN_STORAGE`） |
| jar / dex | 同名 `.json` sidecar | `id`、`version`、`entryClass`、`apiVersion`、可选 `dependencies`（数组）、可选 `permissions`（数组） |

容器里必须是 `classes*.dex`（jar 只放 `.class` 会在加载期 `ClassNotFoundException`）。
产物生成：插件模块跑 `bundlePlugin`，把 APK 的 dex 抽出来封成 jar 并写 sidecar，
见 [plugins/settings/build.gradle.kts](plugins/settings/build.gradle.kts)。

插件类**不得**位于 `org.cordis.*`。

## 共享边界（ABI）

插件类加载器是 child-first，只有共享包从宿主解析。清单的唯一来源是
[gradle/shared-packages.txt](gradle/shared-packages.txt)（19 项），它同时约束三件事：

1. `HostPackages.shared`（插件编译期可见）——[plugin-api/.../HostPackages.kt](plugin-api/src/main/kotlin/org/cordis/api/HostPackages.kt)
2. release 的 R8 `-keep`——[host/proguard-rules.pro](host/proguard-rules.pro)
3. release dex 中该包必须有类——`:host:verifySharedPackagesInDex`

漂移由单测与构建门禁双重拦截（`SharedPackagesContractTest` + `releaseGate`）。
**加一项共享包 = 同时改清单与 `HostPackages.kt`，否则 `:plugin-api:testDebugUnitTest` 失败。**

未列入共享清单的包**不会回落到宿主**：`IsolatedPluginClassLoader.loadClass` 的兜底是
platform class loader（[ModuleLoader.android.kt](loader/src/androidMain/kotlin/org/cordis/loader/ModuleLoader.android.kt)），
所以清单写错 = 插件拿不到该类。曾经的 `com.squareup.okhttp3`（Maven group，非包名）就是这样静默失效的。

### 宿主禁止混淆

`proguard-rules.pro` 置了 `-dontobfuscate`。改名会改写方法签名描述符
（`Continuation`、`Composer`、`Modifier` 等），插件 dex 与宿主 dex 对不上，
加载期直接 `AbstractMethodError`。同时整包 keep 了 kotlin / coroutines /
compose / activity / lifecycle / miuix 等共享运行时——裁剪会删掉插件要用的成员
（首个受害者 `kotlin.jvm.internal.Intrinsics.checkNotNullParameter`）。

## 禁忌依赖

**不要在与系统同名的包下打包类**，R8 内联后会引用系统版本没有的成员：

```
org.json  org.apache.http  org.xmlpull  org.w3c.dom  org.xml.sax  javax.xml  junit
```

宿主侧由 `:host:verifyForbiddenPackages` 在 release 构建时拦截；
插件侧由 `PluginValidator`（`DUPLICATE_PLATFORM_CLASS`）与 `gradle/plugin-verify.gradle.kts` 拦截
（后者还禁止引用 `material-icons-extended`——宿主只发 `icons-core`，图标走 `EvIcons`）。

### 压缩库的可选编解码不受支持

宿主不打包 `zstd` / `brotli` / `asm`（commons-compress 的可选桥接），
对应 `-dontwarn` 已加在 [host/proguard-rules.pro](host/proguard-rules.pro)。
**插件不得依赖 zstd/brotli 压缩路径**，运行时这些类不存在。

## 信任模型

- **APK 插件**必须与宿主证书一致（比对 SHA-256 摘要）。
- **jar/dex** 无法携带 Android 签名。宿主默认**自动信任**（`PluginInstaller.autoTrustUnsigned = true`），
  首次同步即记录 **(id, sha256)** 到应用私有偏好并加载；文件字节变化后，加载期按记录哈希
  复核会拒载（`LOAD_HASH_MISMATCH`）。
  若把 `autoTrustUnsigned` 置为 false，则退回手动流程：停在管理页「待确认的未签名插件」区，
  用户点「信任」后才记录并加载。确认入口始终只在宿主管理页，插件无法触发。
- 加载期二次复核：内核回调 `AndroidModuleVerifier(descriptor)` **只传 descriptor**，宿主在回调里用
  `androidModuleHash(descriptor.file)`（[host/src/main/kotlin/ev/buan/PluginHash.kt](host/src/main/kotlin/ev/buan/PluginHash.kt)）
  自行测量当前 SHA-256，再调 `PluginValidator.verifyAtLoad` 按批准记录与实际哈希重新判定；
  不匹配一律拒载并带错误码（`LOAD_HASH_MISMATCH` / `UNSIGNED_NOT_APPROVED`）。

决策集中在 [PluginValidator.kt](host-core/src/main/kotlin/ev/buan/hostcore/validation/PluginValidator.kt)
（纯 Kotlin，无 Context、无 Android 依赖），错误码：`SIGNATURE_EMPTY`、`SIGNATURE_HOST_MISMATCH`、
`API_VERSION_MISMATCH`、`DUPLICATE_PLATFORM_CLASS`、`ID_HASH_CONFLICT`、
`LOAD_HASH_MISMATCH`、`UNSIGNED_NOT_APPROVED`、`DEPENDENCY_MISSING`、`DEPENDENCY_CYCLE`、
`PERMISSION_DENIED`。

发布签名方案见 [docs/release-signing.md](docs/release-signing.md)（keystore 不入库）。

## 主界面

启动直接进**插件主页**（当前是 `eta-ui` 的 Eta 风格 UI），宿主管理页退到二级：主页左上角进入 `PluginsActivity`。
选择顺序：`home.page` 偏好 → `ev.buan.plugins.eta.ui` → 任意实现 `AgentPage` 的插件 → 兜底页
（[HostRuntime.kt](host/src/main/kotlin/ev/buan/HostRuntime.kt) `homePage()`）。
没有可用主页插件时不会白屏，兜底页仍可进入管理。

`eta-ui` 声明了 `PLUGIN_STORAGE`，会话历史写在
`files/plugins/ev.buan.plugins.eta.ui/sessions.json`。首次安装/升级后需在管理页授予「插件存储」；
未授予时 `eta-ui` 不激活，主页落到宿主兜底页，不会崩。

`eta-ui` 每次发送时查找 `AgentService`（见下节）：装了 brain 就真实收发，
没装/停用则保持原有的「未接模型」占位回复，任何失败都不会崩宿主。

`eta-ui` 抽屉 → 设置 打开插件内部的 Eta 风格设置页：外观六项（主题、Monet、调色板、强调色、
纯黑、界面缩放）通过宿主 `AppearanceService` 修改；模型/工具/记忆/插件管理与宿主设置是跳转入口，
不在插件里复制配置表单。

抽屉还提供三个插件内面板：**工具**只读展示 `ToolRegistry.specs()`（不执行工具）；**浏览器**是
面板内 WebView（地址栏/后退/前进/刷新，不打开外部应用、无标签页/下载）；**记忆**通过
`MemoryService` 查看/清空当前会话的 agent-memory（不删除 eta-ui 聊天历史）。三者都不新增宿主权限。
**角色**面板管理角色库（`characters.json`），角色按会话选择：发送/重新生成时把角色设定作为
`AgentService.send(..., systemPrompt=)` 传入；agent-brain 把它放在本轮模型输入最前，但不写入记忆。
**Skills** 面板管理 `skills.json`，同样按会话选择；发送时把「角色设定 + 当前 Skill instruction」
组合成 systemPrompt，Skill 只作为文本指令，不执行代码、不写记忆。

设置 → **模型服务**（抽屉「模型服务」同入口）是插件内多 Provider 页：Provider 列表/搜索/新增、
配置 Tab（名称、Base URL、API Key、headers、启用、测试连接、保存/重置）与模型 Tab（拉取
`/models`、自定义模型、搜索、设为当前、temperature/topP/maxTokens）。配置存在 agent-model 的
`config/providers.json`，旧 `config/model.json` 首次读取时迁移；**不提供系统提示词字段**。

## 插件 UI

两条渲染路径，按状态需求选：`AgentUi.Content`（可内联，可能组多次，需无状态）与
`AgentPage.Page`（只以独立 Activity 渲染，适合有状态页面）。状态一律放 Cordis 服务。
详见 [docs/plugin-ui-composition.md](docs/plugin-ui-composition.md)。

## agent 能力层

对话能力由一组相互独立的 jar 插件组成，宿主与内核不认识任何 agent 语义；插件之间只通过
`ServiceDirectory` 的 Key（`agent/model`、`agent/tool-registry`、`agent/memory`、`agent/service`）组合：

| 插件 | id | 发布 | 依赖 | 权限 |
|---|---|---|---|---|
| agent-api | `ev.buan.plugins.agent.api` | 契约（接口/数据类） | — | — |
| agent-model | `ev.buan.plugins.agent.model` | `ModelService` + `ModelProviderService`（多 Provider/模型，OpenAI 兼容 chat completions） | agent-api | `NETWORK`、`PLUGIN_STORAGE` |
| agent-tools | `ev.buan.plugins.agent.tools` | `ToolRegistry`（插件存储区文件工具） | agent-api | `PLUGIN_STORAGE` |
| agent-memory | `ev.buan.plugins.agent.memory` | `MemoryService`（JSON 会话记忆） | agent-api | `PLUGIN_STORAGE` |
| agent-brain | `ev.buan.plugins.agent.brain` | `AgentService`（模型 + 工具 + 记忆编排） | agent-api | — |

界面层另有独立插件：

| 插件 | id | 角色 | 依赖 | 权限 |
|---|---|---|---|---|
| eta-ui | `ev.buan.plugins.eta.ui` | **默认主页**（唯一界面插件）：Eta 风格首页/抽屉/聊天/会话历史，消费 `AgentService` | agent-api | `PLUGIN_STORAGE` |

- **安装**：把上表 5 个 agent jar + `eta-ui`（默认主页）的 jar 与同名 json
  一起放进 `EV/mods`；依赖顺序由 sidecar 的 `dependencies` 决定，与文件名无关。`eta-ui` 依赖 agent-api（消费
  `AgentService`），单独放界面插件会被 `DEPENDENCY_MISSING` 拒绝。eta-ui 还需在管理页授予
  「插件存储」才会激活（用于 `sessions.json`）；未授予时主页落到宿主兜底页。agent-api 的 `send` 增加
  可选 `systemPrompt` 后 JVM 签名有变化：agent-api、eta-ui、agent-brain 必须同代重新打包/投放，
  不能混装旧 jar。
- **配置模型**：插件管理 → `ev.buan.plugins.agent.model` 卡片 → 授予「网络访问」「插件存储」；
  再到 `eta-ui` 设置 → 模型服务，配置 Provider/Key/模型并设为当前。旧版单配置
  `config/model.json` 会在首次读取时迁移为「默认」Provider；旧卡片表单已删除。
  多 Provider 配置写在 `config/providers.json`，没有系统提示词字段。
- **降级**：`agent-brain` 每次发送时重新查找 ModelService/ToolRegistry/MemoryService，插件后启或
  先停都不需要重开页面。缺 model → 回复「未连接模型 / 尚未配置」；缺 tools → 纯文本问答；缺 memory
  → 单轮对话；任一能力抛错都变成可读回复，不崩宿主。`eta-ui` 找不到 `AgentService` 时保持
  原来的「未接模型」占位回复。
- **工具轮次**：模型返回 tool call 时由 brain 执行，按原生 OpenAI tool 协议回填——assistant 的
  `tool_calls` 消息在前，每个结果一条 `tool` 角色消息（id 对应），随后再次请求模型
  （每轮最多 3 次、单条结果截断 8 KB，截断不劈开 UTF-16 代理对）。工具轮中的 assistant/tool
  消息一并写入记忆，历史可随时重建模型视角。
- **历史视图有界**：记忆在磁盘上是完整追加日志，发给模型的视图按「轮」裁剪
  （以 user 消息为边界，工具调用与其结果同进同出，最多 20 轮 / 24K 字符，最新一轮永远保留），
  防止长会话的工具输出撑爆上下文窗口。

## 安装与运行

- 插件放 `/storage/emulated/0/EV/mods`，需要「所有文件访问权限」
- 每次启动扫描该目录 → 校验 → 复制进应用私有目录并置只读 → 加载
- 外部文件删除，私有副本同步删除；启用/禁用开关立即生效

## 验证

```bash
scripts/release-smoke.sh          # 单测 + releaseGate + 插件 jar + 真机清单
:plugin-api:testDebugUnitTest     # 插件契约、共享包一致性
:host-core:test                   # 校验器、权限元数据、依赖解析、存储
:ui-kit:testDebugUnitTest         # 外观计算
:loader:testAndroidHostTest       # Android 描述符（AGP9 需 withHostTest）
:plugins:agent-brain:testDebugUnitTest  # agent 编排：未连接降级、工具轮、记忆失败（fake 能力，无网络）
```

agent 层容器（jar + sidecar）用对应模块的 `bundlePlugin` 构建：

```bash
:plugins:agent-api:bundlePlugin :plugins:agent-model:bundlePlugin \
:plugins:agent-tools:bundlePlugin :plugins:agent-memory:bundlePlugin \
:plugins:agent-brain:bundlePlugin :plugins:eta-ui:bundlePlugin
```

## 真机验收清单（agent 层）

前置：真机已授予 EV「所有文件访问权限」；`EV/mods` 内放齐上表 5 个 agent jar 与 `eta-ui` 的
jar/json；真机问答需要一个可用的 OpenAI 兼容服务。本仓库的 CI/本机自动化只覆盖构建与纯逻辑单测
（`AgentBrainServiceTest`），以下条目需人工逐条执行：

> 2026-10-24 已在 PMX110 / Android 17 真机执行 1–8、10 条并通过（真实 DeepSeek 兼容
> 服务，含工具轮与记忆多轮）。完整记录、截图与操作命令见
> [DEVICE-VERIFICATION.md](docs/archive/2026-10-24-arch-hardening/DEVICE-VERIFICATION.md)。

1. **没装或停用 brain**：发送任意消息 → 仍显示「（未接模型）我收到了：…」，不崩溃。
2. **装齐但未配置 model**：发送 → 回复「模型插件已启用但尚未配置…」（或「未连接模型插件…」），
   管理页 `agent-model` 卡片可授予权限并显示配置表单。
3. **全开并保存配置**：在 `agent-model` 卡片授予「网络访问」「插件存储」、填写基址/Key/模型并保存
   （卡片提示「已保存：模型名」）→ 发送「你好」→ 收到真实模型回复，而不是占位文案。
4. **停用 agent-model 后再发送**：→ 回复未就绪文案；重新启用后**无需重开页面**即可继续问答
   （eta-ui 每次发送都重新查找 `AgentService`）。
5. **停用 agent-brain 后再发送**：→ 回到第 1 条的「未接模型」占位回复；重新启用后恢复真实问答。
6. **工具轮**：要求模型写文件（如「把 hello 写入 notes.txt」）→ 工具被执行，最终回复基于工具结果；
   管理页 `agent-tools` 的「插件存储」占用随之变化。
7. **停用 agent-tools 后重复第 6 条**：→ 回复说明工具未启用/工具不存在，不崩溃。
8. **多轮上下文（启用 agent-memory）**：先告诉模型一个名字，再追问「我刚才说了什么名字」→ 能答出。
9. **撤销宿主门控权限**：`NETWORK` 只是声明/审计，撤销不会阻断模型直连；要验证拒载请撤销
   `PLUGIN_STORAGE`（或 `BACKGROUND`）→ 激活/发送出现 `PERMISSION_DENIED`，不崩溃；重新授予后恢复。
10. **崩溃兜底**：插件异常导致进程退出后，下次启动进入安全模式（不加载插件），管理页可退出安全模式。
    本轮用 `files/crash/crash.pending` 模拟该标记；真实崩溃 handler 路径由 host-core 单测覆盖，
    尚未活体触发。

## 已知缺口

- `:host` 是 AGP 9 application 模块，自身有 `:host:test` / `:host:testDebugUnitTest`（权限、存储、安全模式等
  Android 接线测试）；`./gradlew test` 会聚合各模块的 `test`（含 `:host`、`:plugin-api`、`:ui-kit`），
  CI 也跑这一步。可测的纯逻辑放在 `:host-core`
  （校验器、dex 扫描、权限元数据、依赖解析、存储、崩溃标记）。
- `loader/src/androidInstrumentedTest` 仍需 `withDeviceTest` + androidx.test 依赖 + 真机，
  目前未接线（历史遗留，非本轮引入）。
- 插件 UI 抛异常会崩进程：Compose 无错误边界，编译器禁止 `try/catch` 包 composable；
  宿主会记录崩溃并在下次启动进入安全模式（不加载插件），但进程内不恢复、也不是隔离。
- agent 工具轮已切换为原生 OpenAI tool 协议（`AgentMessage.toolCalls`，2026-10-25）；
  供应商缺失 tool call id 时由协议层合成 `call_<index>`。
- agent 层真机问答尚未自动化：CI/本机只跑构建与 `AgentBrainServiceTest`（fake 能力，无网络）。
  2026-10-24 已在 PMX110 / Android 17 上人工执行 1–8、10 条并通过（真实模型工具轮 + 记忆），
  记录与截图见 [DEVICE-VERIFICATION.md](docs/archive/2026-10-24-arch-hardening/DEVICE-VERIFICATION.md)；
  BACKGROUND 前台服务与真实崩溃 handler 仍无活体验证。
- 第三方插件分发**尚不可用**：API 未稳定、信任模型只覆盖自侧载场景。
