# EV（Android 宿主与插件平台）

> **想先看现状：**[docs/project-overview.md](docs/project-overview.md)。本文是仓库门面，
> 只讲格式、边界与禁忌；现状、进度、缺口以那份档案为准。

内核（`core` + `loader`）派生自 cordis-kotlin 并 vendor 在 `third_party/cordis/`，
用法单独成文：[KERNEL.md](KERNEL.md)。

本仓库除内核外还带一个可侧载的 Android 宿主（应用名 EV，模块 `:host-app`）、宿主私有运行时
`:host-runtime`、纯 JVM 逻辑 `:host-core`、设计系统 `:ui-kit`、契约模块 `:host-api`
与 17 个插件模块 `:plugins:<类>:<名>`。**内核语义未改动**，EV 只在其上增加宿主策略。

> **内核已冻结。** `third_party/cordis` 只当依赖用，不接受业务改动；
> `scripts/verify-kernel-lock.sh` 用目录树哈希强制这一点。规则与例外流程见
> [docs/kernel-freeze.md](docs/kernel-freeze.md)。
> 三条架构门禁的入口是 `sh scripts/check.sh`（内核冻结 / 模块边界 / 文件行数），CI 与本地跑的是同一个；
> 另有一道 `scripts/kernel-freeze.sh` 兜住"改内核又顺手刷新 KERNEL.lock"。

> **API 版本 0.x，未稳定。** `PLUGIN_API_VERSION = 1` 只表示当前契约代次，
> 不表示向后兼容承诺；插件与宿主必须同代编译。不要基于它做第三方分发。

## 现在能做什么（2026-11-07）

| 能力 | 现状 | 插件 |
|---|---|---|
| 读屏与操作 | 无障碍读树 + 截图；点按 \/ 滑动 \/ 滚动 \/ 输入 \/ 按键 \/ 条件等待，11 个 `screen_*` 工具 | `agent-gui` |
| 内置浏览器 | 搜索、抓正文、可交互页面（登录 \/ 翻页 \/ 填表）；**多窗口**，agent 与界面共用同一组页面；16 个 `browser_*` \/ `web_*` 工具 | `agent-browse` |
| 设备与文件 | 共享存储读写、应用启动、剪贴板、通知、亮度 \/ 音量 \/ 勿扰 \/ 手电筒等；28 个工具 | `agent-device` |
| 个人数据 | 日历（读写）、闹钟、便签（只读）；14 个工具 | `agent-phone` |
| root shell | 单条命令，带总线级审批与"灾难命令"拒绝清单 | `agent-shell` |
| 记忆 | 三层：会话全量 \/ 可检索笔记 \/ 常驻提示词；空闲后自动蒸馏 | `agent-memory` |
| 设备端编译 | 在手机上编插件（26 MB 载荷，默认关闭、单独安装） | `forge` + `forge-compiler` |

合计约 **86 个工具**（按 `ToolSpec` 计），17 个插件全部可独立装卸，宿主 APK 与插件分开分发。

要求 **Android 13（API 33）及以上**；`run_shell`、设备开关这类能力需要 root。发布包见
[Releases](https://github.com/buanys/EV/releases)。

## 插件格式

一个插件 = **一个容器 + 一份元数据**：

| 形态 | 元数据位置 | 字段 |
|---|---|---|
| APK | AndroidManifest 的 `<meta-data>` | `cordis.entry`（入口类全名）、`cordis.api`（契约版本）、`cordis.dependencies`（JSON 数组字符串，如 `["ev.buan.plugins.ping.api"]`）、`cordis.permissions`（逗号分隔字符串，如 `NETWORK,PLUGIN_STORAGE`） |
| jar / dex | 同名 `.json` sidecar | `id`、`version`、`entryClass`、`apiVersion`、可选 `dependencies`（数组）、可选 `permissions`（数组） |

容器里必须是 `classes*.dex`（jar 只放 `.class` 会在加载期 `ClassNotFoundException`）。
产物生成：插件模块跑 `bundlePlugin`，把 APK 的 dex 抽出来封成 jar 并写 sidecar，
见 [plugins/base/agent-device/build.gradle.kts](plugins/base/agent-device/build.gradle.kts)。

插件类**不得**位于平台、Kotlin 运行时、内核或共享包所在的任何前缀下（完整清单在
[PluginPolicy.kt](host-runtime/src/main/kotlin/ev/buan/PluginPolicy.kt)，拒绝码是 `DUPLICATE_PLATFORM_CLASS`）。

## 共享边界（ABI）

插件类加载器**先查共享包**，命中就从宿主解析；其余才查插件自己的 dex、再查它声明的依赖容器、
最后才是 platform loader。清单的唯一来源是
[gradle/shared-packages.txt](gradle/shared-packages.txt)（20 项），它同时约束三件事：

1. `HostPackages.shared`（插件编译期可见）——[host-api/.../HostPackages.kt](host-api/src/main/kotlin/org/cordis/api/HostPackages.kt)
2. release 的 R8 `-keep`——[host-app/proguard-rules.pro](host-app/proguard-rules.pro)
3. release dex 中该包必须有类——`:host-app:verifySharedPackagesInDex`

漂移由单测与构建门禁双重拦截（`SharedPackagesContractTest` + `releaseGate`）。
**加一项共享包 = 同时改清单、`HostPackages.kt` 与 proguard 规则，否则 `SharedPackagesContractTest` 失败。**

未列入共享清单的包**不会回落到宿主**：`IsolatedPluginClassLoader.loadClass` 的兜底是
platform class loader（[ModuleLoader.android.kt](third_party/cordis/loader/src/androidMain/kotlin/org/cordis/loader/ModuleLoader.android.kt)），
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

宿主侧由 `:host-app:verifyForbiddenPackages` 在 release 构建时拦截；
插件侧由 `PluginValidator`（`DUPLICATE_PLATFORM_CLASS`）与 `gradle/plugin-verify.gradle.kts` 拦截
（后者还禁止引用 `material-icons-extended`——宿主只发 `icons-core`，图标走 `EvIcons`）。

### 压缩库的可选编解码不受支持

宿主不打包 `zstd` / `brotli` / `asm`（commons-compress 的可选桥接），
对应 `-dontwarn` 已加在 [host-app/proguard-rules.pro](host-app/proguard-rules.pro)。
**插件不得依赖 zstd/brotli 压缩路径**，运行时这些类不存在。

## 信任模型

- **APK 插件**必须与宿主证书一致（比对 SHA-256 摘要）。
- **jar/dex** 无法携带 Android 签名，所以宿主按 **`FLAG_DEBUGGABLE`** 分两种：
  - **调试包**：自动信任，首次同步记录 `(id, sha256)` 后直接加载；
  - **正式包**：恒为手动——容器停在管理页「待确认的未签名插件」区，用户点「信任」后才记录并加载。

  绑在可调试标志上而不是一个常量，是因为"关掉确认"的副作用会跟着代码一起打进正式版：
  调试包丢进目录即可跑，正式包不可能忘记改回来。
- 确认是**按字节**的：文件哈希一变就要重新确认，加载期按记录复核（`LOAD_HASH_MISMATCH`）。
- 确认入口始终只在宿主管理页，插件无法触发。未签名插件的能力面另由 [ADR-0008](docs/adr/0008-trust-by-capability-surface.md) 约束。
- 加载期二次复核：内核回调 `AndroidModuleVerifier(descriptor)` **只传 descriptor**，宿主在回调里用
  `androidModuleHash(descriptor.file)`（[host-runtime/src/main/kotlin/ev/buan/PluginHash.kt](host-runtime/src/main/kotlin/ev/buan/PluginHash.kt)）
  自行测量当前 SHA-256，再调 `PluginValidator.verifyAtLoad` 按批准记录与实际哈希重新判定；
  不匹配一律拒载并带错误码（`LOAD_HASH_MISMATCH` / `UNSIGNED_NOT_APPROVED`）。

决策集中在 [PluginValidator.kt](host-core/src/main/kotlin/ev/buan/hostcore/validation/PluginValidator.kt)
（纯 Kotlin，无 Context、无 Android 依赖），错误码：`SIGNATURE_EMPTY`、`SIGNATURE_HOST_MISMATCH`、
`API_VERSION_MISMATCH`、`DUPLICATE_PLATFORM_CLASS`、`ID_HASH_CONFLICT`、
`LOAD_HASH_MISMATCH`、`UNSIGNED_NOT_APPROVED`、`DEPENDENCY_MISSING`、`DEPENDENCY_CYCLE`、
`PERMISSION_DENIED`。

发布签名方案见 [docs/release-signing.md](docs/release-signing.md)（keystore 不入库）。

## 主界面

启动直接进**插件主页**（当前是 `ev-ui` 的 EV 风格 UI），宿主管理页退到二级：主页左上角进入 `PluginsActivity`。
选择顺序：`home.page` 偏好 → `ev.buan.plugins.ev.ui` → 任意实现 `AgentPage` 的插件 → 兜底页
（[HostRouting.kt](host-runtime/src/main/kotlin/ev/buan/HostRouting.kt) 与 `HostComposition.homePage()`）。
没有可用主页插件时不会白屏，兜底页仍可进入管理。

`ev-ui` 声明了 `PLUGIN_STORAGE`，会话历史写在
`files/plugins/ev.buan.plugins.ev.ui/sessions.json`。首次安装/升级后需在管理页授予「插件存储」；
未授予时 `ev-ui` 不激活，主页落到宿主兜底页，不会崩。

`ev-ui` 每次发送时查找 `AgentService`（见下节）：装了 brain 就真实收发，
没装/停用则保持原有的「未接模型」占位回复，任何失败都不会崩宿主。

`ev-ui` 抽屉 → 设置 打开插件内部的 EV 风格设置页：外观六项（主题、Monet、调色板、强调色、
纯黑、界面缩放）通过宿主 `AppearanceService` 修改；模型/工具/记忆/插件管理与宿主设置是跳转入口，
不在插件里复制配置表单。

抽屉还提供三个插件内面板：**工具**只读展示 `ToolRegistry.specs()`（不执行工具）；**浏览器**是
内置引擎的界面壳——多窗口（标签条，上限 5 个）、地址栏/前进后退/刷新、`target=_blank` 开新窗口、
下载交给系统，外链 `tel:`/`mailto:`/`intent:` 同样交出去；WebView 归宿主，界面与 agent 操作的是
同一组页面，离开面板不销毁；**记忆**通过
`MemoryService` 查看/清空当前会话的 agent-memory（不删除 ev-ui 聊天历史）。三者都不新增宿主权限。
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

**对话链路**上的四颗（完整 17 颗见 [project-overview §5](docs/project-overview.md)）：

| 插件 | id | 发布 | 位置 | 权限 |
|---|---|---|---|---|
| agent-model | `ev.buan.plugins.agent.model` | `ModelService` + `ModelProviderService`（多 Provider/模型，OpenAI 兼容） | `plugins/integration/` | `NETWORK`、`PLUGIN_STORAGE` |
| agent-tools | `ev.buan.plugins.agent.tools` | `ToolRegistry`（插件存储区文件工具） | `plugins/base/` | `PLUGIN_STORAGE` |
| agent-memory | `ev.buan.plugins.agent.memory` | `MemoryService`（三层记忆） | `plugins/base/` | `PLUGIN_STORAGE` |
| agent-brain | `ev.buan.plugins.agent.brain` | `AgentService`（模型 + 工具 + 记忆编排） | `plugins/feature/` | — |

界面层另有 `ev-ui`（`ev.buan.plugins.ev.ui`，`plugins/feature/`）：**默认主页**，消费 `AgentService`，
声明 `PLUGIN_STORAGE`（会话历史）。

- **安装**：把要用的插件 jar + 同名 json 放进 `EV/mods`；加载顺序由 sidecar 的 `dependencies` 决定，
  与文件名无关。契约（`AgentService`、`ToolRegistry`…）已经并进宿主 APK，不是需要单独安装的插件。
- ev-ui 需要「插件存储」才会激活（用于 `sessions.json`），未授予时主页落到宿主兜底页，不崩。
- 升级时宿主 APK 与插件要同代投放：契约签名一旦变化，旧 jar 会在类加载期失败，而不是静默降级。
- **配置模型**：插件管理 → `ev.buan.plugins.agent.model` 卡片 → 授予「网络访问」「插件存储」；
  再到 `ev-ui` 设置 → 模型服务，配置 Provider/Key/模型并设为当前。旧版单配置
  `config/model.json` 会在首次读取时迁移为「默认」Provider；旧卡片表单已删除。
  多 Provider 配置写在 `config/providers.json`，没有系统提示词字段。
- **降级**：`agent-brain` 每次发送时重新查找 ModelService/ToolRegistry/MemoryService，插件后启或
  先停都不需要重开页面。缺 model → 回复「未连接模型 / 尚未配置」；缺 tools → 纯文本问答；缺 memory
  → 单轮对话；任一能力抛错都变成可读回复，不崩宿主。`ev-ui` 找不到 `AgentService` 时保持
  原来的「未接模型」占位回复。
- **工具轮次**：模型返回 tool call 时由 brain 执行，按原生 OpenAI tool 协议回填——assistant 的
  `tool_calls` 消息在前，每个结果一条 `tool` 角色消息（id 对应），随后再次请求模型。
  一轮的预算默认是 **20 次模型往返 / 60 次工具调用 / 5 分钟**，超了会被掐断并如实说明原因；
  单条工具结果截断 8 K 字符，截断不劈开 UTF-16 代理对。工具轮中的 assistant/tool
  消息一并写入记忆，历史可随时重建模型视角。
- **历史视图有界**：记忆在磁盘上是完整追加日志，发给模型的视图按「轮」裁剪
  （以 user 消息为边界，工具调用与其结果同进同出，最多 20 轮 / 24K 字符，最新一轮永远保留），
  防止长会话的工具输出撑爆上下文窗口。

## 安装与运行

- **最快的方式**：从 [Releases](https://github.com/buanys/EV/releases) 拿 `EV-<版本>.apk` 与
  `EV-<版本>-plugins.zip`，解压插件到 `/storage/emulated/0/EV/mods`（jar 与同名 json 必须成对）
- 装过 debug 签名（或不同发布密钥）的旧版无法覆盖安装，**必须先卸载**；应用数据会丢，
  `/sdcard/EV` 下的插件文件不受影响
- 插件放 `/storage/emulated/0/EV/mods`，需要「所有文件访问权限」
- 每次启动扫描该目录 → 校验 → 复制进应用私有目录并置只读 → 加载
- 外部文件删除，私有副本同步删除；启用/禁用开关立即生效

## 验证

```bash
sh scripts/check.sh                       # 三条架构门禁（内核冻结 / 模块边界 / 行数）
./gradlew test                            # 全部单测（CI 跑的就是这一条；含内核 JVM 测试）
./gradlew assembleDebug                   # 宿主 APK
sh scripts/release-smoke.sh               # 单测 + releaseGate + 插件 jar

./gradlew :host-api:apiSnapshotCheck :ui-kit:apiSnapshotCheck   # SDK 公开面是否变了
```

`apiSnapshotCheck` 是**任何 public 声明变化都要跑**的：契约与设计系统的基线在 `api/*.txt`，
改完用 `./gradlew :host-api:apiSnapshot :ui-kit:apiSnapshot` 刷新。

插件容器（jar + sidecar）用对应模块的 `bundlePlugin`：

```bash
./gradlew :plugins:integration:agent-model:bundlePlugin
./gradlew :plugins:feature:ev-ui:bundlePlugin
```

⚠️ `bundlePlugin` 有时会**假绿**：源文件改了仍复用旧 jar。部署前看时间戳，或加 `--rerun-tasks`。
它也不兼容配置缓存，批量打的时候带 `--no-configuration-cache`。

CI 在 push 到 `main` 时跑 [.github/workflows/android.yml](.github/workflows/android.yml)：
内核冻结 → `assembleDebug` → 全量单测 → SDK 单测 → API 快照 → `:host-app:releaseGate`。

## 真机验收清单（agent 层）

> 下面是一份**历史验收清单**（2026-10-24 执行）。插件数量与名字后来变了（现在 17 个），
> 条目本身仍然有效，当"改完 agent 链路怎么验"的模板用。

前置：真机已授予 EV「所有文件访问权限」；`EV/mods` 内放齐 agent 链路的 jar 与 `ev-ui` 的
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
   （ev-ui 每次发送都重新查找 `AgentService`）。
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

- `:host-app` 是 AGP 9 application 模块，入口与 Android 接线测试在它和 `:host-runtime` 里；
  `./gradlew test` 会聚合所有模块的 `test`，CI 也跑这一步。可测的纯逻辑放在 `:host-core`
  （校验器、dex 扫描、权限元数据、依赖解析、存储、崩溃标记）。
- `third_party/cordis/loader` 的 `androidInstrumentedTest` 仍需 `withDeviceTest` + androidx.test 依赖 + 真机，
  目前未接线（历史遗留）。
- 插件 UI 抛异常会崩进程：Compose 无错误边界，编译器禁止 `try/catch` 包 composable；
  宿主会记录崩溃并在下次启动进入安全模式（不加载插件），但进程内不恢复、也不是隔离。
- agent 工具轮已切换为原生 OpenAI tool 协议（`AgentMessage.toolCalls`，2026-10-25）；
  供应商缺失 tool call id 时由协议层合成 `call_<index>`。
- agent 层真机问答尚未自动化：CI/本机只跑构建与 `AgentBrainServiceTest`（fake 能力，无网络）。
  2026-10-24 已在 PMX110 / Android 17 上人工执行 1–8、10 条并通过（真实模型工具轮 + 记忆），
  记录与截图见 [DEVICE-VERIFICATION.md](docs/archive/2026-10-24-arch-hardening/DEVICE-VERIFICATION.md)；
  BACKGROUND 前台服务与真实崩溃 handler 仍无活体验证。
- 第三方插件分发**尚不可用**：API 未稳定、信任模型只覆盖自侧载场景。
- CI 里跳过了内核的一个时序用例（`FiberLifecycleTest > config update coalesces…`，只在 runner 上
  失败；内核冻结不能改）。本地仍会跑它，原因与删除条件见
  [docs/build-on-device.md](docs/build-on-device.md)。
- 自动出包（`release.yml`，推 `v*` tag 触发）需要仓库配置 `KEYSTORE_BASE64` 与
  `KEYSTORE_PROPERTIES` 两个 secret；没配时它会带着可读错误失败，不会产出 debug 签名的包。
  当前 v0.1.0 是本地构建后手工发布的。
- `forge-compiler` 不在发布包里：它的载荷要 `scripts/forge-payload.sh` 生成，默认关闭、按需安装。

## 来源与许可

内核部分派生自 [meteor149/cordis-kotlin](https://github.com/meteor149/cordis-kotlin)（MIT，
版权 Shigma）；上游的版权声明与许可条款原样保留在 [LICENSE](LICENSE) 中，派生范围、新增部分
和第三方依赖说明见 [NOTICE.md](NOTICE.md)。

本项目以 MIT 许可证发布，详见 [LICENSE](LICENSE)。
