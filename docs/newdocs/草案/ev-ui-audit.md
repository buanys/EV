# ev-ui 旧项目逐文件审计（只读）

- 审计对象：`archive/ev-ui-legacy/`（旧项目 ev-ui 的只读归档，40 个文件）。
- 审计方式：逐文件完整阅读；import 用脚本统计；未运行 gradle、未运行旧代码、未上真机。
- 事实来源：本文件主体（§§1–7、§9）仅取自 `archive/ev-ui-legacy/` 原文，以及 `docs/最高规则/ev-final-rules.md`、`docs/规范/ev-plan.md`、`docs/规范/ev-manifest-spec.md`；仓库模块是否存在的结论另以 `settings.gradle.kts` 与目录现状核对，文中会明确标注。§8 附录由并行的 ui-kit 任务提供，不在本任务 40 文件范围，本审计未逐行复核。
- 行号约定：引用归档文件时，`M/` 表示 `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/`，`T/` 表示 `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/`；`M/EvUiPlugin.kt:121` 可还原为完整相对路径。§1 盘点表使用完整相对路径。
- 行数约定：按文件可见行统计。仅 `EvChatColumn.kt` 末尾无换行，`wc -l` 报 280、可见 281 行；因此 40 个文件可见行合计 9308，`wc -l` 合计 9307（与任务描述的「约 9307 行」一致）。
- 审计边界：总规则 `:5`「1. 新 EV 是一个新项目。旧 EV 归档为只读，不再在旧结构上修改。」；`:107`「8. 旧仓库打只读标签，并附说明：基线哈希不存在、门禁依赖不存在的提交、内核为 SNAPSHOT。」——本审计只读 `archive/ev-ui-legacy/`，未改动归档。
- 处置类别四选一：设计资产 / 渲染参考 / 外壳参考 / 丢弃。

## 1. 逐文件盘点表

| 序号 | 文件路径 | 类型 | 行数 | 主要职责（一句话） | 处置类别 |
|---:|---|---|---:|---|---|
| 1 | `archive/ev-ui-legacy/build.gradle.kts` | 构建脚本 | 126 | 把旧插件按 Android application 构建，声明 compileOnly 宿主/邻居模块依赖、测试依赖，并打出 dex+metadata 容器。 | 丢弃 |
| 2 | `archive/ev-ui-legacy/src/main/AndroidManifest.xml` | 清单 | 19 | 旧 Cordis 插件元数据（entry/api/PLUGIN_STORAGE/agent-api 依赖），入口类写成 eta 包。 | 丢弃 |
| 3 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ApprovalGate.kt` | Kotlin | 92 | 实现 ApprovalService，排队危险工具审批并支持「本次运行内放行」。 | 丢弃 |
| 4 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ChatConversation.kt` | Kotlin | 254 | 会话/消息/工具步骤数据模型，以及删除、重生成、事件折叠等纯函数。 | 丢弃 |
| 5 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ChatSessionController.kt` | Kotlin | 296 | 每会话回合控制：发送、流式进度、取消、持久化、删除与重生成。 | 丢弃 |
| 6 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ChatSessionDocuments.kt` | Kotlin | 134 | `sessions.json` 的 org.json 编解码与会话/消息字段映射。 | 丢弃 |
| 7 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ChatSessionStore.kt` | Kotlin | 57 | 通过旧 PluginStorage 读写会话文档，读失败按空历史、写失败只记日志。 | 丢弃 |
| 8 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatChrome.kt` | Kotlin | 254 | 首页顶栏、模型详情卡、二级页返回栏与浮动顶按钮。 | 外壳参考 |
| 9 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatColumn.kt` | Kotlin | 281 | 会话页容器：滚动区、顶/底渐变、顶栏与输入区装配。 | 外壳参考 |
| 10 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatData.kt` | Kotlin | 74 | 会话标题推导与按本地日期分组，供抽屉和首页最近会话使用。 | 丢弃 |
| 11 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatDrawer.kt` | Kotlin | 98 | 把抽屉 UI 与会话选择/删除、页面跳转和设置入口接线。 | 外壳参考 |
| 12 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatPane.kt` | Kotlin | 215 | 消息列表、空态、最近会话和输入中指示的布局与节奏。 | 渲染参考 |
| 13 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvChatUiState.kt` | Kotlin | 42 | 聊天页瞬态 UI 状态（草稿、抽屉、搜索、面板、页面栈）。 | 丢弃 |
| 14 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvComposerArea.kt` | Kotlin | 331 | 输入区容器、`+` 面板、模型 sheet，以及沿卡片轮廓跑的边缘流光。 | 外壳参考 |
| 15 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvRouting.kt` | Kotlin | 99 | 全屏页面密封接口、返回栈，以及其它插件 id 常量。 | 丢弃 |
| 16 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvUiPlugin.kt` | Kotlin | 642 | 旧插件入口：inject 宿主服务、发布审批服务、组合首页/页面、全局路由与自报 manifest。 | 丢弃 |
| 17 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/EvWorkProcess.kt` | Kotlin | 360 | 工具调用折叠时间线：分组、耗时、运行/失败状态与展开节点。 | 渲染参考 |
| 18 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/RoundTrace.kt` | Kotlin | 107 | 把 AgentProgress 事件折叠成轮次/步骤时间线的纯 reducer。 | 丢弃 |
| 19 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvApprovalDialog.kt` | Kotlin | 145 | 高危操作审批模态框，以及「本次运行不再询问 / 允许一次 / 拒绝」三键。 | 丢弃 |
| 20 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvChat.kt` | Kotlin | 387 | 消息气泡、入场动画、复制/重生成/删除、工具步骤卡与三点输入指示。 | 渲染参考 |
| 21 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvComposer.kt` | Kotlin | 519 | 输入框两态连续形变、发送/麦克风/停止键、模型 chip、模型 sheet 与 `+` 面板。 | 外壳参考 |
| 22 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvDrawer.kt` | Kotlin | 533 | 抽屉视觉：品牌头、导航行、按日期分组的历史、搜索与删除确认。 | 外壳参考 |
| 23 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvHomeHero.kt` | Kotlin | 175 | 空对话首页：标题、建议卡、最近会话与能力状态行。 | 外壳参考 |
| 24 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvLibraryParts.kt` | Kotlin | 166 | 角色/技能编辑器复用的表单控件、提示与两段式删除。 | 外壳参考 |
| 25 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvLinuxScreen.kt` | Kotlin | 187 | Linux 环境状态/安装页，经工具总线和插件目录服务直接操作 linux 插件。 | 丢弃 |
| 26 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvMarkdown.kt` | Kotlin | 438 | 手写 Markdown 子集渲染：段落、标题、列表、表格、代码块与行内样式。 | 渲染参考 |
| 27 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvModelProvidersScreen.kt` | Kotlin | 898 | 模型 Provider/模型列表、编辑、搜索、连接测试与参数编辑页。 | 丢弃 |
| 28 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvMoreMenu.kt` | Kotlin | 95 | 右侧 2×2 快捷工具菜单（终端/浏览器/两个占位）。 | 外壳参考 |
| 29 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvPluginCatalogScreen.kt` | Kotlin | 409 | 插件能力清单与详情：启停、清存储、授予/撤销权限、编辑插件设置。 | 外壳参考 |
| 30 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvSettingsScreen.kt` | Kotlin | 260 | 插件内设置页：外观、模型、工具、记忆、Linux、插件与 HUD 调试开关。 | 外壳参考 |
| 31 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvTextSummary.kt` | Kotlin | 15 | 把长描述截成单行摘要的文本工具。 | 丢弃 |
| 32 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/EvThemePickerScreen.kt` | Kotlin | 50 | 主题列表选择页，选中后写宿主外观状态。 | 外壳参考 |
| 33 | `archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/ModelProviderState.kt` | Kotlin | 239 | 模型 Provider 页的纯状态、过滤、摘要、草稿校验与合并规则。 | 丢弃 |
| 34 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ApprovalGateTest.kt` | 测试 | 120 | 审批队列、会话放行、取消与 denyAll 的行为测试。 | 丢弃 |
| 35 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ChatConversationTest.kt` | 测试 | 194 | 会话/消息 id、删除、重生成和工具步骤折叠的纯函数测试。 | 丢弃 |
| 36 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ChatSessionControllerTest.kt` | 测试 | 191 | 发送、并发拒绝、选择、删除、重生成与持久化的控制器测试。 | 丢弃 |
| 37 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ChatSessionDocumentsTest.kt` | 测试 | 240 | 会话 JSON 往返、坏数据降级与旧字段兼容测试。 | 丢弃 |
| 38 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ChatSessionStoreTest.kt` | 测试 | 146 | 存储缺失、损坏、配额与原子写失败的行为测试。 | 丢弃 |
| 39 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/RoundTraceTest.kt` | 测试 | 87 | 事件折叠、跳过步骤与取消后无残留的测试。 | 丢弃 |
| 40 | `archive/ev-ui-legacy/src/test/kotlin/ev/buan/plugins/ev/ui/ui/ModelProviderStateTest.kt` | 测试 | 333 | Provider 过滤、当前模型、参数校验与远端刷新合并的测试。 | 丢弃 |

类型计数：Kotlin 主代码 31 个、测试 7 个、构建脚本 1 个、清单 1 个，合计 40。

## 2. 构建依赖提取

### 2.1 plugins 块与 Android 配置

| 声明 | 值 | 行号 |
|---|---|---|
| `plugins {}` 块 | 两个插件，均无版本号 | `build.gradle.kts:1-4` |
| 插件 `com.android.application` | 无版本号（由旧根工程 classpath 决定） | `build.gradle.kts:2` |
| 插件 `org.jetbrains.kotlin.plugin.compose` | 无版本号 | `build.gradle.kts:3` |
| `namespace` | `ev.buan.plugins.ev.ui` | `build.gradle.kts:7` |
| `compileSdk` | 37 | `build.gradle.kts:8` |
| `applicationId` | `ev.buan.plugins.ev.ui` | `build.gradle.kts:11` |
| `minSdk` | 33 | `build.gradle.kts:12` |
| `targetSdk` | 37 | `build.gradle.kts:13` |
| `versionCode` / `versionName` | 1 / `1.1.0` | `build.gradle.kts:14-15` |
| `buildFeatures.compose` | `true` | `build.gradle.kts:18-20` |
| `release.isMinifyEnabled` | `false` | `build.gradle.kts:22-26` |
| Java 源/目标兼容 | 17 / 17 | `build.gradle.kts:28-31` |

结论：旧 ev-ui 是一个可独立产出 APK 的 **application**，不是新规则中的 `plugins/*` 库模块；它自带 `applicationId`，并以 APK 抽 dex 的方式交付（`build.gradle.kts:89-97`）。

### 2.2 依赖坐标、版本与作用域

| 依赖坐标 / 声明 | 版本 | 作用域 | 行号 | 说明 |
|---|---|---|---|---|
| `project(":ui-kit")` | 项目依赖，无版本 | `compileOnly` | `build.gradle.kts:48` | 旧宿主设计系统模块；本仓库不存在 |
| `project(":plugin-api")` | 项目依赖，无版本 | `compileOnly` | `build.gradle.kts:49` | 旧宿主/内核契约模块；本仓库不存在 |
| `project(":loader")` | 项目依赖，无版本 | `compileOnly` | `build.gradle.kts:50` | 旧内核 loader；本仓库不存在 |
| `files(agentApiClasses)` | 文件依赖，无版本 | `compileOnly` | `build.gradle.kts:43-45, :51` | `plugins/agent-api/build/intermediates/.../classes.jar`；本仓库不存在 |
| `androidx.activity:activity-compose` | `1.13.0` | `compileOnly` | `build.gradle.kts:57` | 注释称由宿主提供 |
| `androidx.navigationevent:navigationevent-compose` | `1.1.2` | `compileOnly` | `build.gradle.kts:58` | 固定宿主解析结果 |
| `junit:junit` | `4.13.2` | `testImplementation` | `build.gradle.kts:64` | JUnit4 |
| `files(agentApiClasses)` | 文件依赖，无版本 | `testImplementation` | `build.gradle.kts:65` | 测试也要另一插件的类输出 |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.4.20` | `testImplementation` | `build.gradle.kts:66` | 测试用 stdlib |
| `androidx.compose.runtime:runtime` | `1.12.0` | `testImplementation` | `build.gradle.kts:70` | 仅为满足 Compose 编译器检查 |
| `org.json:json` | `20260814` | `testImplementation` | `build.gradle.kts:74` | 测试用独立 org.json |
| `project(":plugin-api")` | 项目依赖，无版本 | `testImplementation` | `build.gradle.kts:75` | 测试用宿主契约 |
| `project(":loader")` | 项目依赖，无版本 | `testImplementation` | `build.gradle.kts:76` | 测试用内核类型 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.10.2` | `testImplementation` | `build.gradle.kts:79` | 测试用 `runBlocking` |

### 2.3 文件依赖、exclude 与旧构建接线

- `agentApiClasses` 定义为 `rootProject.file("plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar")`：`build.gradle.kts:43-45`。注释自述原因是 AGP 不允许依赖 application 模块（`:40-42`），这等于用构建产物路径绕开模块契约。
- `compileOnly(files(agentApiClasses))`：`:51`；`testImplementation(files(agentApiClasses))`：`:65`。
- 排除 stdlib：对名字以 `RuntimeClasspath` 结尾且不含 `UnitTest` 的配置排除 `org.jetbrains.kotlin:kotlin-stdlib`（`:36-38`）。注释称在运行时由宿主提供（`:34-35`）。
- 任务 `bundlePluginJar` 从 debug APK 抽出 `classes*.dex`，打包成 `ev-ui.jar`（`:89-97`）；`bundlePlugin` 生成 `ev-ui.json` sidecar（`:99-118`）。
- sidecar 内容：`id`（`:108`）、`version`（`:109`）、`entryClass`（`:110`）、`apiVersion`（`:111`）、`dependencies: ["ev.buan.plugins.agent.api"]`（`:112`）、`permissions: ["PLUGIN_STORAGE"]`（`:113`）。
- `apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))`：`:120`。
- `preBuild` 依赖 `:plugins:agent-api:bundleDebugClassesToCompileJar`：`:124-126`。

### 2.4 外部模块不存在于本仓库（事实核对）

归档声明的四个外部模块在本仓库都不存在：

| 归档声明 | 行号 | 本仓库现状 |
|---|---|---|
| `project(":ui-kit")` | `build.gradle.kts:48` | 根目录无 `ui-kit/` 项目；`settings.gradle.kts:13` 未 include，模块坐标不可解析 |
| `project(":plugin-api")` | `build.gradle.kts:49, :75` | 无 `plugin-api/` 目录；`settings.gradle.kts:13` 未 include，模块坐标不可解析 |
| `project(":loader")` | `build.gradle.kts:50, :76` | 无 `loader/` 目录；`settings.gradle.kts:13` 未 include，模块坐标不可解析 |
| `plugins/agent-api/.../classes.jar` | `build.gradle.kts:43-45, :51, :65`；任务 `:125` | 无 `plugins/agent-api/` 目录；该文件依赖无法解析 |

补充：

- `settings.gradle.kts:13` 的模块清单为 `include(":host-api", ":host-core", ":host-android", ":host-runtime", ":app", ":plugins:base-first")`，没有上述四个模块，也没有旧的 `:plugin-api`/`:loader`。模块存在性以这条 `include` 列表为准；未纳入 include 的同名目录不构成 Gradle 模块。
- `gradle/` 下只有 `gradle/wrapper/`；`gradle/plugin-verify.gradle.kts` 不存在，因此 `build.gradle.kts:120` 的 `apply` 无法解析。
- 旧构建从未依赖本仓库的 `:host-api`。全部 `project(...)` 都指向已不存在的模块，因此归档代码单独不可编译；按要求本次未运行 gradle 验证。

## 3. import 分类

### 3.1 统计方法与总数

统计命令（只算 `.kt`，含测试）：

```bash
find archive/ev-ui-legacy -name '*.kt' -print0 | xargs -0 grep -nH '^import ' | wc -l
# 831
```

- `.kt` 文件 38 个：主代码 31 个，测试 7 个。
- import 总数 **831**：主代码 **772**，测试 **59**。
- 分类按 import 全限定名前缀归并；同一行只计一次。

| 类别 | 条数 | 占比 | 包含的前缀 |
|---|---:|---:|---|
| Compose（`androidx.compose.*`） | 512 | 61.6% | 主代码 510 + 测试 2 |
| AndroidX 非 Compose（`androidx.activity.*`） | 1 | 0.1% | `BackHandler` |
| Android/Java/Kotlin 标准库 | 19 | 2.3% | `java.*` 15、`kotlin.math.*` 3、`android.util.Log` 1 |
| 第三方库 | 78 | 9.4% | `org.junit.*` 34、`top.yukonga.miuix.*` 22、`kotlinx.coroutines.*` 19、`org.json.*` 3 |
| 宿主相关包 | 182 | 21.9% | `org.cordis.*` 32、`ev.buan.plugins.agent.api.*` 45、`ev.buan.ui.*` 105 |
| 同插件内部包 | 39 | 4.7% | `ev.buan.plugins.ev.ui.*` |
| **合计** | **831** | **100%** | — |

说明：

- 归档中**没有**对 `ev.host`、`ev.core`、`host-runtime` 或 `loader` 类的直接 import；所谓「宿主相关」实际是旧内核/宿主 API `org.cordis.*`、旧设计系统 `ev.buan.ui.*`、旧 agent 契约 `ev.buan.plugins.agent.api.*`。这三类都不是新规则的 `host-api`。
- `top.yukonga.miuix` 按第三方 UI 库计，不按宿主包计；但它与 `ev.buan.ui` 一起构成旧界面的样式来源。
- 测试的 59 条中，`org.junit` 34 条、`kotlinx.coroutines` 8 条、宿主相关 14 条、Compose 2 条、`java.io` 1 条。

### 3.2 各类示例（文件:行号）

- Compose：`M/EvUiPlugin.kt:3-45`（`androidx.activity.compose.BackHandler`、`androidx.compose.*`）、`M/ui/EvChat.kt:3-36`。
- Android/Java 标准库：`M/EvChatData.kt:5-8`（`java.time.*`）、`M/ChatConversation.kt:6`（`java.util.UUID`）、`M/EvComposerArea.kt:17-18`（`kotlin.math.min/sin`）、`M/ChatSessionStore.kt:3`（`android.util.Log`）。
- `kotlinx.coroutines`：`M/ApprovalGate.kt:9`、`M/ChatSessionController.kt:10-15`、`M/ui/EvLinuxScreen.kt:34`、`M/ui/EvModelProvidersScreen.kt:46-47`。
- `org.json`：`M/ChatSessionDocuments.kt:4-5`、`M/ui/EvLinuxScreen.kt:36`。
- `org.junit`：`T/ApprovalGateTest.kt:8-11`、`T/ChatSessionStoreTest.kt:5-8`。
- `top.yukonga.miuix`：`M/ui/EvDrawer.kt:51`、`M/ui/EvChat.kt:44-45`、`M/EvUiPlugin.kt:96-97`。
- 宿主相关 `org.cordis`：`M/EvUiPlugin.kt:82-95`、`M/ui/EvSettingsScreen.kt:26-30`。
- 宿主相关 `agent.api`：`M/EvUiPlugin.kt:46-51`、`M/ui/ModelProviderState.kt:3-7`。
- 宿主相关 `ev.buan.ui`（旧 ui-kit）：`M/EvChatColumn.kt:43-45`、`M/ui/EvChat.kt:40-43`。
- 同插件内部：`M/EvUiPlugin.kt:52-71`、`M/ui/EvChat.kt:38-39`。

### 3.3 宿主相关 import 逐条清单（182 条）

以下每条均为一条 import，格式 `文件:行号 全限定名`；`M/`、`T/` 定义见文首。

- `M/ApprovalGate.kt:6` `ev.buan.plugins.agent.api.ApprovalDecision`
- `M/ApprovalGate.kt:7` `ev.buan.plugins.agent.api.ApprovalService`
- `M/ChatConversation.kt:3` `ev.buan.plugins.agent.api.AgentEvent`
- `M/ChatConversation.kt:4` `ev.buan.plugins.agent.api.AgentStop`
- `M/ChatConversation.kt:5` `ev.buan.plugins.agent.api.StepState`
- `M/ChatSessionController.kt:7` `ev.buan.plugins.agent.api.AgentProgress`
- `M/ChatSessionController.kt:8` `ev.buan.plugins.agent.api.AgentReply`
- `M/ChatSessionController.kt:9` `ev.buan.plugins.agent.api.AgentService`
- `M/ChatSessionController.kt:16` `org.cordis.api.ServiceDirectory`
- `M/ChatSessionDocuments.kt:3` `ev.buan.plugins.agent.api.AgentEvent`
- `M/ChatSessionStore.kt:4` `org.cordis.api.PluginStorage`
- `M/EvChatChrome.kt:3` `ev.buan.ui.evHairline`
- `M/EvChatChrome.kt:26` `ev.buan.ui.EvGlyph`
- `M/EvChatChrome.kt:27` `ev.buan.ui.EvGlyphKind`
- `M/EvChatChrome.kt:28` `ev.buan.ui.evGlassFloating`
- `M/EvChatChrome.kt:29` `ev.buan.ui.EvIcons`
- `M/EvChatChrome.kt:30` `ev.buan.ui.EvThemeTokens`
- `M/EvChatColumn.kt:9` `ev.buan.ui.evGlassLayer`
- `M/EvChatColumn.kt:10` `ev.buan.ui.evGlassOver`
- `M/EvChatColumn.kt:21` `ev.buan.ui.EvGlyphKind`
- `M/EvChatColumn.kt:39` `ev.buan.plugins.agent.api.MemoryService`
- `M/EvChatColumn.kt:40` `ev.buan.plugins.agent.api.ToolRegistry`
- `M/EvChatColumn.kt:41` `ev.buan.plugins.agent.api.ModelService`
- `M/EvChatColumn.kt:43` `ev.buan.ui.EvMotionTokens`
- `M/EvChatColumn.kt:44` `ev.buan.ui.EvPage`
- `M/EvChatColumn.kt:45` `ev.buan.ui.EvThemeTokens`
- `M/EvChatColumn.kt:46` `org.cordis.api.ServiceDirectory`
- `M/EvChatDrawer.kt:7` `ev.buan.ui.EvPage`
- `M/EvChatPane.kt:24` `ev.buan.ui.EvThemeTokens`
- `M/EvComposerArea.kt:27` `ev.buan.ui.evGlassLayer`
- `M/EvComposerArea.kt:48` `ev.buan.ui.EvGlyphKind`
- `M/EvComposerArea.kt:49` `ev.buan.ui.EvMotionTokens`
- `M/EvComposerArea.kt:50` `ev.buan.ui.EvThemeTokens`
- `M/EvUiPlugin.kt:46` `ev.buan.plugins.agent.api.AgentService`
- `M/EvUiPlugin.kt:47` `ev.buan.plugins.agent.api.ApprovalService`
- `M/EvUiPlugin.kt:48` `ev.buan.plugins.agent.api.MemoryService`
- `M/EvUiPlugin.kt:49` `ev.buan.plugins.agent.api.ModelProviderService`
- `M/EvUiPlugin.kt:50` `ev.buan.plugins.agent.api.ModelService`
- `M/EvUiPlugin.kt:51` `ev.buan.plugins.agent.api.ToolRegistry`
- `M/EvUiPlugin.kt:72` `ev.buan.ui.EvDrawerScaffold`
- `M/EvUiPlugin.kt:73` `ev.buan.ui.EvDrawerSide`
- `M/EvUiPlugin.kt:74` `ev.buan.ui.EvGlyph`
- `M/EvUiPlugin.kt:75` `ev.buan.ui.EvGlyphKind`
- `M/EvUiPlugin.kt:76` `ev.buan.ui.EvIcons`
- `M/EvUiPlugin.kt:77` `ev.buan.ui.EvMotionTokens`
- `M/EvUiPlugin.kt:78` `ev.buan.ui.EvPageTransitions`
- `M/EvUiPlugin.kt:79` `ev.buan.ui.EvThemeTokens`
- `M/EvUiPlugin.kt:82` `org.cordis.Context`
- `M/EvUiPlugin.kt:83` `org.cordis.Dependencies`
- `M/EvUiPlugin.kt:84` `org.cordis.EffectScope`
- `M/EvUiPlugin.kt:85` `org.cordis.Plugin`
- `M/EvUiPlugin.kt:86` `org.cordis.api.AgentPage`
- `M/EvUiPlugin.kt:87` `org.cordis.api.AgentSurface`
- `M/EvUiPlugin.kt:88` `org.cordis.api.AppearanceService`
- `M/EvUiPlugin.kt:89` `org.cordis.api.HostNavigation`
- `M/EvUiPlugin.kt:90` `org.cordis.api.PluginPageService`
- `M/EvUiPlugin.kt:91` `org.cordis.api.PluginStorage`
- `M/EvUiPlugin.kt:92` `org.cordis.api.UserDataStorage`
- `M/EvUiPlugin.kt:93` `org.cordis.api.migrateToUserData`
- `M/EvUiPlugin.kt:94` `org.cordis.api.ServiceDirectory`
- `M/EvUiPlugin.kt:95` `org.cordis.dependencies`
- `M/EvWorkProcess.kt:30` `ev.buan.plugins.agent.api.AgentEvent`
- `M/EvWorkProcess.kt:31` `ev.buan.plugins.agent.api.StepState`
- `M/EvWorkProcess.kt:32` `ev.buan.ui.EvGlyph`
- `M/EvWorkProcess.kt:33` `ev.buan.ui.EvGlyphKind`
- `M/EvWorkProcess.kt:34` `ev.buan.ui.EvMotionTokens`
- `M/EvWorkProcess.kt:35` `ev.buan.ui.EvThemeTokens`
- `M/RoundTrace.kt:3` `ev.buan.plugins.agent.api.AgentProgress`
- `M/RoundTrace.kt:4` `ev.buan.plugins.agent.api.StepState`
- `M/ui/EvApprovalDialog.kt:25` `ev.buan.plugins.agent.api.ApprovalDecision`
- `M/ui/EvApprovalDialog.kt:27` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvChat.kt:37` `ev.buan.plugins.agent.api.AgentEvent`
- `M/ui/EvChat.kt:40` `ev.buan.ui.EvGlyph`
- `M/ui/EvChat.kt:41` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvChat.kt:42` `ev.buan.ui.EvMotionTokens`
- `M/ui/EvChat.kt:43` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvComposer.kt:3` `ev.buan.ui.evGlassLayer`
- `M/ui/EvComposer.kt:4` `ev.buan.ui.evHairline`
- `M/ui/EvComposer.kt:7` `ev.buan.ui.EvGlyph`
- `M/ui/EvComposer.kt:8` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvComposer.kt:9` `ev.buan.ui.EvMotionTokens`
- `M/ui/EvComposer.kt:10` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvDrawer.kt:47` `ev.buan.ui.EvGlyph`
- `M/ui/EvDrawer.kt:48` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvDrawer.kt:49` `ev.buan.ui.EvMotionTokens`
- `M/ui/EvDrawer.kt:50` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvHomeHero.kt:22` `ev.buan.ui.EvGlyph`
- `M/ui/EvHomeHero.kt:23` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvHomeHero.kt:24` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvLibraryParts.kt:24` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvLibraryParts.kt:25` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvLibraryParts.kt:26` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvLibraryParts.kt:27` `ev.buan.ui.StatusError`
- `M/ui/EvLinuxScreen.kt:27` `ev.buan.plugins.agent.api.ToolRegistry`
- `M/ui/EvLinuxScreen.kt:28` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvLinuxScreen.kt:29` `ev.buan.ui.EvPage`
- `M/ui/EvLinuxScreen.kt:30` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvLinuxScreen.kt:31` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvLinuxScreen.kt:32` `ev.buan.ui.EvSettingsSection`
- `M/ui/EvLinuxScreen.kt:33` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvLinuxScreen.kt:35` `org.cordis.api.PluginCatalogService`
- `M/ui/EvMarkdown.kt:29` `ev.buan.ui.EvGlyph`
- `M/ui/EvMarkdown.kt:30` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvMarkdown.kt:31` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvModelProvidersScreen.kt:31` `ev.buan.plugins.agent.api.ModelDescriptor`
- `M/ui/EvModelProvidersScreen.kt:32` `ev.buan.plugins.agent.api.ModelParameters`
- `M/ui/EvModelProvidersScreen.kt:33` `ev.buan.plugins.agent.api.ModelProvider`
- `M/ui/EvModelProvidersScreen.kt:34` `ev.buan.plugins.agent.api.ModelProviderService`
- `M/ui/EvModelProvidersScreen.kt:35` `ev.buan.ui.EvEmptyState`
- `M/ui/EvModelProvidersScreen.kt:36` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvModelProvidersScreen.kt:37` `ev.buan.ui.EvPage`
- `M/ui/EvModelProvidersScreen.kt:38` `ev.buan.ui.EvSettingsDivider`
- `M/ui/EvModelProvidersScreen.kt:39` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvModelProvidersScreen.kt:40` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvModelProvidersScreen.kt:41` `ev.buan.ui.EvSettingsSection`
- `M/ui/EvModelProvidersScreen.kt:42` `ev.buan.ui.EvSettingsTrailing`
- `M/ui/EvModelProvidersScreen.kt:43` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvModelProvidersScreen.kt:44` `ev.buan.ui.StatusError`
- `M/ui/EvModelProvidersScreen.kt:45` `ev.buan.ui.StatusSuccess`
- `M/ui/EvMoreMenu.kt:4` `ev.buan.ui.evPressable`
- `M/ui/EvMoreMenu.kt:21` `ev.buan.ui.EvGlyph`
- `M/ui/EvMoreMenu.kt:22` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvMoreMenu.kt:23` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvPluginCatalogScreen.kt:22` `ev.buan.ui.EvGlyph`
- `M/ui/EvPluginCatalogScreen.kt:23` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvPluginCatalogScreen.kt:24` `ev.buan.ui.EvPage`
- `M/ui/EvPluginCatalogScreen.kt:25` `ev.buan.ui.EvSettingsDivider`
- `M/ui/EvPluginCatalogScreen.kt:26` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvPluginCatalogScreen.kt:27` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvPluginCatalogScreen.kt:28` `ev.buan.ui.EvSettingsSection`
- `M/ui/EvPluginCatalogScreen.kt:36` `ev.buan.ui.EvSettingsTrailing`
- `M/ui/EvPluginCatalogScreen.kt:37` `ev.buan.ui.EvThemeTokens`
- `M/ui/EvPluginCatalogScreen.kt:38` `org.cordis.api.PluginCapability`
- `M/ui/EvPluginCatalogScreen.kt:39` `org.cordis.api.ManagedPlugin`
- `M/ui/EvPluginCatalogScreen.kt:40` `org.cordis.api.PluginCatalogService`
- `M/ui/EvPluginCatalogScreen.kt:41` `org.cordis.api.PluginManagerService`
- `M/ui/EvPluginCatalogScreen.kt:42` `org.cordis.api.PluginManifest`
- `M/ui/EvPluginCatalogScreen.kt:43` `org.cordis.api.PluginSetting`
- `M/ui/EvSettingsScreen.kt:13` `ev.buan.ui.EvAccentColor`
- `M/ui/EvSettingsScreen.kt:14` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvSettingsScreen.kt:15` `ev.buan.ui.EvPage`
- `M/ui/EvSettingsScreen.kt:16` `ev.buan.ui.EvPaletteStyle`
- `M/ui/EvSettingsScreen.kt:17` `ev.buan.ui.EvSettingsDivider`
- `M/ui/EvSettingsScreen.kt:18` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvSettingsScreen.kt:19` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvSettingsScreen.kt:20` `ev.buan.ui.EvSettingsSection`
- `M/ui/EvSettingsScreen.kt:21` `ev.buan.ui.EvSettingsTrailing`
- `M/ui/EvSettingsScreen.kt:22` `ev.buan.ui.EvThemeMode`
- `M/ui/EvSettingsScreen.kt:23` `ev.buan.ui.MAX_INTERFACE_SCALE`
- `M/ui/EvSettingsScreen.kt:24` `ev.buan.ui.MIN_INTERFACE_SCALE`
- `M/ui/EvSettingsScreen.kt:25` `ev.buan.ui.normalizeInterfaceScale`
- `M/ui/EvSettingsScreen.kt:26` `org.cordis.api.AppearanceService`
- `M/ui/EvSettingsScreen.kt:27` `org.cordis.api.HudPulse`
- `M/ui/EvSettingsScreen.kt:28` `org.cordis.api.HudService`
- `M/ui/EvSettingsScreen.kt:29` `org.cordis.api.HudState`
- `M/ui/EvSettingsScreen.kt:30` `org.cordis.api.PLUGIN_API_VERSION`
- `M/ui/EvThemePickerScreen.kt:5` `ev.buan.ui.EvGlyphKind`
- `M/ui/EvThemePickerScreen.kt:6` `ev.buan.ui.EvPage`
- `M/ui/EvThemePickerScreen.kt:7` `ev.buan.ui.EvSettingsDivider`
- `M/ui/EvThemePickerScreen.kt:8` `ev.buan.ui.EvSettingsGroup`
- `M/ui/EvThemePickerScreen.kt:9` `ev.buan.ui.EvSettingsRow`
- `M/ui/EvThemePickerScreen.kt:10` `ev.buan.ui.EvSettingsSection`
- `M/ui/EvThemePickerScreen.kt:11` `ev.buan.ui.EvSettingsTrailing`
- `M/ui/ModelProviderState.kt:3` `ev.buan.plugins.agent.api.ModelDescriptor`
- `M/ui/ModelProviderState.kt:4` `ev.buan.plugins.agent.api.ModelParameters`
- `M/ui/ModelProviderState.kt:5` `ev.buan.plugins.agent.api.ModelProvider`
- `M/ui/ModelProviderState.kt:6` `ev.buan.plugins.agent.api.ModelProviderService`
- `M/ui/ModelProviderState.kt:7` `ev.buan.plugins.agent.api.ModelRef`
- `T/ApprovalGateTest.kt:3` `ev.buan.plugins.agent.api.ApprovalDecision`
- `T/ChatConversationTest.kt:3` `ev.buan.plugins.agent.api.AgentEvent`
- `T/ChatSessionControllerTest.kt:9` `org.cordis.api.PluginStorage`
- `T/ChatSessionDocumentsTest.kt:3` `ev.buan.plugins.agent.api.AgentEvent`
- `T/ChatSessionStoreTest.kt:3` `org.cordis.api.PluginStorage`
- `T/ChatSessionStoreTest.kt:4` `org.cordis.api.PluginStorageQuotaExceededException`
- `T/RoundTraceTest.kt:3` `ev.buan.plugins.agent.api.AgentProgress`
- `T/RoundTraceTest.kt:4` `ev.buan.plugins.agent.api.StepState`
- `T/ui/ModelProviderStateTest.kt:3` `ev.buan.plugins.agent.api.ModelDescriptor`
- `T/ui/ModelProviderStateTest.kt:4` `ev.buan.plugins.agent.api.ModelParameters`
- `T/ui/ModelProviderStateTest.kt:5` `ev.buan.plugins.agent.api.ModelProvider`
- `T/ui/ModelProviderStateTest.kt:6` `ev.buan.plugins.agent.api.ModelProviderService`
- `T/ui/ModelProviderStateTest.kt:7` `ev.buan.plugins.agent.api.ModelRef`
- `T/ui/ModelProviderStateTest.kt:8` `ev.buan.plugins.agent.api.ModelTestResult`

### 3.4 同插件内部 import（39 条）

按文件计数：`EvUiPlugin.kt` 20、`EvChatPane.kt` 7、`EvComposerArea.kt` 4、`EvChatData.kt` 2、`EvChat.kt` 2、`EvChatChrome.kt` 1、`EvChatColumn.kt` 1、`EvChatDrawer.kt` 1、`EvApprovalDialog.kt` 1，合计 39。它们引用同插件 `ui/` 子包的 Composable 或数据类，属于插件内部组织，不构成跨模块依赖；文件拆分类参考见 §4。

## 4. 处置建议（四选一）

判定口径：

- **设计资产**：文件主体是数值、令牌、动效参数、颜色定义。
- **渲染参考**：文件主体是消息或内容的视觉实现，逻辑可参考但需按宿主渲染器接口重写。
- **外壳参考**：顶栏、导航、页面容器，仅视觉参考，代码全部重写。
- **丢弃**：与新规则冲突，或只是接线、状态管理、与旧插件体系绑定的代码。

| 序号 | 文件 | 处置 | 理由与证据 |
|---:|---|---|---|
| 1 | `archive/ev-ui-legacy/build.gradle.kts` | 丢弃 | 旧 application 构建与 `applicationId`（`:2`、`:11`），并直接 compileOnly 宿主/邻居模块（`:48-51`），自报 `PLUGIN_STORAGE`（`:113`）；整体不可迁移。 |
| 2 | `archive/ev-ui-legacy/src/main/AndroidManifest.xml` | 丢弃 | 旧 Cordis 元数据（`:4-17`），且 entry 指向 `ev.buan.plugins.eta.ui.EtaUiPlugin`（`:6`），与源码不符。 |
| 3 | `M/ApprovalGate.kt` | 丢弃 | 在插件内实现 `ApprovalService`（`:30`）并提供会话级放行（`:45`、`:69`）；审批裁决属宿主（总规则 `:37-44`）。 |
| 4 | `M/ChatConversation.kt` | 丢弃 | 绑定旧 `agent.api` 类型（`:3-5`），是会话/消息业务模型与列表编辑逻辑（`:17-24`、`:145-150`），非视觉。 |
| 5 | `M/ChatSessionController.kt` | 丢弃 | 回合状态机与持久化接线（`:26`、`:121-226`），并直接查找其它插件服务（`:130`）；属状态管理/旧接线。 |
| 6 | `M/ChatSessionDocuments.kt` | 丢弃 | 旧 `sessions.json` 格式与 org.json 编解码（`:20-22`、`:27-46`），没有独立设计价值。 |
| 7 | `M/ChatSessionStore.kt` | 丢弃 | 旧 PluginStorage 读写与失败策略（`:4`、`:26`、`:40`），绑定旧权限/存储体系。 |
| 8 | `M/EvChatChrome.kt` | 外壳参考 | 首页顶栏（`:102-202`）、页面返回栏（`:206-228`）与浮动按钮（`:237-254`）的视觉可参考；入口归宿主，代码重写。 |
| 9 | `M/EvChatColumn.kt` | 外壳参考 | 页面容器、顶/底渐变与滚动层（`:90-280`）是页面骨架，不是消息本身。 |
| 10 | `M/EvChatData.kt` | 丢弃 | 标题推导（`:25-32`）与日期分组（`:34-73`）属业务数据逻辑。 |
| 11 | `M/EvChatDrawer.kt` | 外壳参考 | 抽屉导航接线（`:17-97`）是导航外壳；视觉可参考、代码重写。 |
| 12 | `M/EvChatPane.kt` | 渲染参考 | 消息列表的同侧/换侧间距、空态与最近会话布局（`:38-176`）是消息内容视觉。 |
| 13 | `M/EvChatUiState.kt` | 丢弃 | 草稿/抽屉/搜索/页面栈等瞬态状态（`:18-41`），属状态管理。 |
| 14 | `M/EvComposerArea.kt` | 外壳参考 | 输入区容器与 `+` 面板（`:98-189`）、边缘流光（`:203-310`）是输入外壳；动效参数可提取（`:318-331`）。 |
| 15 | `M/EvRouting.kt` | 丢弃 | 页面栈实现（`:60-87`）与硬编码的其它插件 id（`:89-99`），属导航状态与旧插件耦合。 |
| 16 | `M/EvUiPlugin.kt` | 丢弃 | 旧插件入口/装配：实现 `Plugin`、`AgentSurface`、`ManifestProvider`（`:121`），inject 宿主服务（`:127-139`），发布审批服务（`:201-208`），自报 manifest（`:597-606`）。 |
| 17 | `M/EvWorkProcess.kt` | 渲染参考 | 工具工作折叠卡、时间线、耗时与状态文案（`:50-146`、`:149-164`）是内容视觉；逻辑需按新事件接口重写。 |
| 18 | `M/RoundTrace.kt` | 丢弃 | `AgentProgress` 到时间线的 reducer（`:44-78`），绑定旧 agent 事件契约。 |
| 19 | `M/ui/EvApprovalDialog.kt` | 丢弃 | 三个决定按钮与「本次运行不再询问」（`:99-115`）把裁决语义带进 UI；视觉可另绘。 |
| 20 | `M/ui/EvChat.kt` | 渲染参考 | 消息气泡、无气泡的助手正文、流式光标、复制/重生成/删除（`:86-149`）与入场动效（`:158-180`）是消息渲染。 |
| 21 | `M/ui/EvComposer.kt` | 外壳参考 | 输入框两态形变、发送键三态、模型 chip/sheet（`:135-284`、`:425-473`、`:481-518`）是输入外壳。 |
| 22 | `M/ui/EvDrawer.kt` | 外壳参考 | 抽屉品牌头、导航、会话历史、搜索与删除确认（`:97-185`、`:60-70`）是导航视觉。 |
| 23 | `M/ui/EvHomeHero.kt` | 外壳参考 | 首页空态、建议卡与最近会话（`:43-118`、`:121-148`）是入口视觉。 |
| 24 | `M/ui/EvLibraryParts.kt` | 外壳参考 | 表单字段/提示/删除确认等设置页组件（`:43-166`）；但角色/技能编辑器不在归档，调用面见 U-07。 |
| 25 | `M/ui/EvLinuxScreen.kt` | 丢弃 | 按工具名直连 `linux_status`/`linux_setup`（`:64`、`:80`），并硬编码 `ev.buan.plugins.linux`（`:187`），是旧跨插件接线。 |
| 26 | `M/ui/EvMarkdown.kt` | 渲染参考 | 助手正文的 Markdown 块解析与样式（`:74-92`、`:243-438`）是内容渲染；解析器可参考、需按宿主文本组件重写。 |
| 27 | `M/ui/EvModelProvidersScreen.kt` | 丢弃 | 页主体是旧 `ModelProviderService` 的读写与表单状态（`:64`、`:282-401`、`:561-804`），属集成/配置业务。 |
| 28 | `M/ui/EvMoreMenu.kt` | 外壳参考 | 右侧 2×2 快捷菜单视觉（`:35-67`），是外壳。 |
| 29 | `M/ui/EvPluginCatalogScreen.kt` | 外壳参考 | 插件目录/详情页视觉（`:52-237`）可参考；启停与授权逻辑（`:157`、`:183-184`）归宿主，需丢弃。 |
| 30 | `M/ui/EvSettingsScreen.kt` | 外壳参考 | 设置页分组与行样式（`:46-210`）是外壳；设置归属与 HUD 写入逻辑需重写。 |
| 31 | `M/ui/EvTextSummary.kt` | 丢弃 | 单行截断工具（`:10-14`），随设置页一并丢弃，无独立价值。 |
| 32 | `M/ui/EvThemePickerScreen.kt` | 外壳参考 | 主题列表页视觉（`:20-50`）可参考；主题登记归宿主，代码重写。 |
| 33 | `M/ui/ModelProviderState.kt` | 丢弃 | Provider 状态/摘要/参数校验纯函数（`:29-41`、`:74-85`、`:209-232`），绑定旧模型服务业务。 |
| 34 | `T/ApprovalGateTest.kt` | 丢弃 | 测旧审批门（`:18-22`），随实现丢弃。 |
| 35 | `T/ChatConversationTest.kt` | 丢弃 | 测旧会话模型/工具步骤（`:15-18`），随实现丢弃。 |
| 36 | `T/ChatSessionControllerTest.kt` | 丢弃 | 测旧控制器与 PluginStorage（`:25-67`），随实现丢弃。 |
| 37 | `T/ChatSessionDocumentsTest.kt` | 丢弃 | 测旧 JSON 文档格式（`:17-56`），随实现丢弃。 |
| 38 | `T/ChatSessionStoreTest.kt` | 丢弃 | 测旧存储失败路径（`:15-19`），随实现丢弃。 |
| 39 | `T/RoundTraceTest.kt` | 丢弃 | 测旧 `AgentProgress` 折叠（`:10-14`），随实现丢弃。 |
| 40 | `T/ui/ModelProviderStateTest.kt` | 丢弃 | 测旧 Provider 状态（`:19-23`），随实现丢弃。 |

**分类统计**：设计资产 **0**、渲染参考 **4**（EvChatPane、EvWorkProcess、EvChat、EvMarkdown）、外壳参考 **12**（EvChatChrome、EvChatColumn、EvChatDrawer、EvComposerArea、EvComposer、EvDrawer、EvHomeHero、EvLibraryParts、EvMoreMenu、EvPluginCatalogScreen、EvSettingsScreen、EvThemePickerScreen）、丢弃 **24**。合计 0+4+12+24=40。

关于「设计资产为 0」的说明：归档里没有以令牌/颜色/动效参数为主体的文件。数值常量分散嵌在渲染文件中，例如 `EvChat.kt:56-70`（`EvChatDefaults`：页边距、气泡宽度、同侧/换侧间距、44dp 触控）、`EvComposer.kt:59-107`（`EvComposerDefaults`：圆角、输入框高度、面板尺寸）、`EvComposerArea.kt:318-331`（边缘光周期/拖尾/分段与 `Color(0xFF5AAEFF)`）、`EvWorkProcess.kt:166-167`（3 秒耗时阈值）。真正的令牌定义在归档外的旧 `ui-kit`（`ev.buan.ui.EvThemeTokens`、`EvMotionTokens`，共 105 条 import），归档本身拿不到具体数值；这些常量应作为「设计资产线索」在重写时提取，但按文件主体判定，不把任何一个文件整体归为设计资产。

## 5. 冲突清单

以下 16 条均为实质冲突；每条给出归档证据与规则原文。`ev-final-rules.md` 简称「总规则」。

### C-01 以独立 application 构建插件，并自带 applicationId

- 证据：`build.gradle.kts:2` `id("com.android.application")`；`:11` `applicationId = "ev.buan.plugins.ev.ui"`；`:89-97` 从 debug APK 抽 `classes*.dex` 打成 `ev-ui.jar`。
- 违反：总规则 `:98`「4. 模块划分：`host-api`（契约，版本严格管理）、`host-runtime`（宿主私有，插件类路径不可见）、`plugins/*`（每个插件一个模块，按四类分放）。」；`ev-plan.md:217`「1.新增一个 app/ 模块，Android application，它是唯一能产出 APK 的模块。」
- 说明：旧 ev-ui 既当 application 又当插件容器；新结构里插件是 `plugins/*` 模块，APK 只能由 `app/` 产出。

### C-02 `compileOnly project(...)` 直接依赖宿主/内核模块

- 证据：`build.gradle.kts:48` `compileOnly(project(":ui-kit"))`、`:49` `compileOnly(project(":plugin-api"))`、`:50` `compileOnly(project(":loader"))`；测试侧 `:75`、`:76` 再依赖 `:plugin-api`、`:loader`。
- 违反：总规则 `:84`「1. 插件只能依赖契约包。插件可用的内核类型，要么由契约包重新导出，要么经契约包的接口间接使用。插件不能直接依赖宿主私有包（host-runtime）。」；`ev-plan.md:38`「- `plugins/*` → `host-api`」。
- 说明：`:plugin-api`/`:loader` 是旧宿主/内核模块，`:ui-kit` 是旧设计系统模块；没有一个是新契约 `host-api`。归档也从未声明 `host-api` 依赖。

### C-03 用文件路径依赖另一个插件的构建产物 `agentApiClasses`

- 证据：`build.gradle.kts:43-45` 硬编码 `plugins/agent-api/build/intermediates/.../classes.jar`；`:51` compileOnly、`:65` testImplementation；`:124-126` 让 `preBuild` 依赖 `:plugins:agent-api:bundleDebugClassesToCompileJar`。
- 违反：总规则 `:84`（只能依赖契约包）；`:100`「6. 服务依赖统一走 `inject`，插件在清单中声明接口和版本，宿主装配时检查。描述符中的模块依赖只允许宿主声明，插件不能自行添加。」
- 说明：这绕开了接口与版本检查，把另一个插件的内部构建路径当成编译接口；新规则要求通过清单 `requires`/`inject` 声明服务依赖。

### C-04 运行时 classpath 排除 `kotlin-stdlib`

- 证据：`build.gradle.kts:36-38` 对所有 `*RuntimeClasspath`（UnitTest 除外）排除 `org.jetbrains.kotlin:kotlin-stdlib`；注释 `:34-35` 明说「Everything here comes from the host at runtime」。
- 违反：总规则 `:84`（插件只能依赖契约包，不能假定宿主私有类路径）；`:98`「`host-runtime`（宿主私有，插件类路径不可见）」。
- 说明：旧容器假定宿主会注入 stdlib 与共享包；新规则下插件模块独立编译、只通过 `host-api` 使用宿主类型，不能靠裁剪运行时 classpath 维持类身份。

### C-05 旧 metadata 自报 `PLUGIN_STORAGE` 权限与 agent-api 依赖

- 证据：`build.gradle.kts:106-116` 生成 sidecar，其中 `:112` `"dependencies": ["ev.buan.plugins.agent.api"]`、`:113` `"permissions": ["PLUGIN_STORAGE"]`；`AndroidManifest.xml:10-13` 同样写 `cordis.permissions = PLUGIN_STORAGE`。
- 违反：总规则 `:86`「3. 权限、信任、登记全部在宿主，内核里没有。内核只回答"能不能注册、能不能撤销"，不回答"能不能做"。」；`ev-manifest-spec.md:10`「文件名固定为 `ev-plugin.json`……」；`:102`「出现未知值，拒绝安装。」（权限闭合集合见 `:100-101`，不含 `PLUGIN_STORAGE`）。
- 说明：旧 metadata 让插件自带权限声明，且权限名不在新闭合集合内。归档中**没有** `trustTier`/`signed`/`trusted` 字段，因此只能判定「自称权限/依赖」，不能判定「自称信任」；但权限声明本身已违反宿主统一开放权限的规则。

### C-06 清单入口类写错包名，且与构建 sidecar 不一致

- 证据：`AndroidManifest.xml:5-6` `cordis.entry = "ev.buan.plugins.eta.ui.EtaUiPlugin"`；源码实际为 `M/EvUiPlugin.kt:1` `package ev.buan.plugins.ev.ui`、`:121` `class EvUiPlugin`；而 `build.gradle.kts:110` sidecar 写的是 `"entryClass": "ev.buan.plugins.ev.ui.EvUiPlugin"`。
- 违反：`ev-manifest-spec.md:34`「| `entry` | 字符串 | 入口类的全限定名。 |」；`:176`「8. `entry`、页面 `entry` 指向的类是否存在于 artifact 中（只检查存在，不加载）。」
- 说明：按旧清单加载会找不到 `eta.ui.EtaUiPlugin`，或误指向不存在的历史包。到底哪个入口是旧工程实际使用的，需要旧仓库历史确认（见 U-01）。

### C-07 插件承担宿主首页、外壳与返回栈

- 证据：`M/EvUiPlugin.kt:121` 实现 `AgentSurface, AgentPage`；`:216-222` `Page`/`Home` 直接渲染 `ChatSurface`；`:273-293` 插件安装 `BackHandler` 管理页面栈；`:495-568` 用 `EvDrawerScaffold` 搭整个首页；`M/EvChatColumn.kt:90-280` 页面容器；`M/EvChatChrome.kt:102-202` 顶栏。
- 违反：总规则 `:7`「3. 宿主只做机制和裁决，不做能力。没有任何插件时也必须能独立工作。」；`:85`「2. 宿主是唯一的装配者。插件的加载、注册、卸载都由宿主通过内核原语完成，插件不能自己调用内核的装载接口。」；`ev-plan.md:219`「3.入口页和插件管理页放在 app/，不放在 host-android。host-android 只做适配，不含界面……」
- 说明：总规则 `:57`「└── 插件 【宿主不理解它们的业务内容】」、`:64`「├── 功能插件 【页面和行为】」表明插件可以有页面；但旧 ev-ui 同时是宿主首页、返回栈与抽屉外壳，卸载它宿主就没有主界面，违反「没有任何插件也能独立工作」。

### C-08 直接 inject/require 旧宿主服务，并在清单之外发布服务

- 证据：`M/EvUiPlugin.kt:127-139` `dependencies(HostNavigation.Key, ServiceDirectory.Key, PluginStorage.Key, UserDataStorage.Key, AppearanceService.Key, HudService.Key)`；`:184-190` `ctx.require(...)` 与 `migrateToUserData`；`:201-208` `dir.publish(ApprovalService.Key, approvalGate)` 并在 effect 中 retract；`M/EvChatColumn.kt:46`、`M/ChatSessionController.kt:16` 直接依赖 `ServiceDirectory`。
- 违反：总规则 `:84`（只能依赖契约包）；`:100`（服务依赖走 `inject`，在清单中声明接口和版本）；`ev-manifest-spec.md:94-95`「- 插件在 `apply` 中注册的服务集合，必须与 `provides` 完全一致。- 注册了清单之外的服务，立即拒绝激活，并记入失败记录。」
- 说明：inject 列表硬编码在源码里，含旧宿主导航/外观/HUD/存储服务；`ApprovalService` 没有出现在任何 `provides` 清单中，属于清单之外的服务注册。

### C-09 插件自建审批门，替宿主做裁决

- 证据：`M/ApprovalGate.kt:30` `class ApprovalGate : ApprovalService`；`:39-58` 排队等待人类；`:45` / `:69` 会话级放行会短路后续请求；`M/EvUiPlugin.kt:198-208` 把 gate 发布为宿主服务；`M/ui/EvApprovalDialog.kt:99-115` 三个决定按钮，其中「本次运行不再询问」是插件持有的长期授权。
- 违反：总规则 `:37-38`「裁决 【谁能做什么，用户同不同意】」「权限：宿主申请、记录、撤销，按清单统一开放给插件」；`:86`（权限、信任、登记全部在宿主）；`ev-plan.md:124`「5. **准入**：检查权限声明，高风险权限未同意则不准入。宿主裁决负责。」；`:137`「权限的授予和撤销只能由裁决层执行，插件不能请求自我授权。」
- 说明：危险工具审批是宿主裁决职责；旧 UI 自己维护等待队列与会话级授权；按新规则，审批与授权记录必须由宿主裁决层持有，插件侧不能作为最终授权来源。旧宿主是否另有审计日志，归档内不可见（U-05）。

### C-10 绕过权限门/隔离档位，直接调用其它插件服务

- 证据：`M/ChatSessionController.kt:130` `directory?.lookup(AgentService.Key)`，`:137-141` 直接 `agent.sendStreaming(...)`；`M/EvUiPlugin.kt:626-629` `PluginPageService.pageOf(pluginId)` 后直接调用 `page.Page(...)`；`M/ui/EvLinuxScreen.kt:64` / `:80` 按工具名 lookup 后 `invoke`（`:94-97`）。
- 违反：总规则 `:84`（只能依赖契约包）；`ev-plan.md:130`「受限档位插件的加载：步骤 7 改为在独立进程中加载，宿主通过 broker 转发调用。」；`:134`「所有插件可访问的宿主接口，都必须经过一道权限门，入口在 `host-api` 的调用签名处统一拦截。」
- 说明：旧代码通过 `ServiceDirectory` 在同进程直接取服务/页面/工具，调用点没有权限参数，也没有受限档位的 broker 边界；新规则下这类调用必须由宿主按清单和权限门转发。旧宿主是否在服务实现内另设门槛，归档内看不到（U-05）。

### C-11 硬编码其它插件 id、页面与工具名

- 证据：`M/EvRouting.kt:89-99` 写死 `ev.buan.plugins.agent.memory/tools/shell/browse`；`M/ui/EvLinuxScreen.kt:64` / `:80` 工具名 `linux_status`/`linux_setup`、`:187` `ev.buan.plugins.linux`；`M/EvUiPlugin.kt:405-472` 按这些 id 路由页面。
- 违反：总规则 `:82`「依赖方向单向：插件 → 契约包（host-api）→ 内核原语；宿主 → 内核原语和契约包。内核不依赖宿主，也不知道插件存在。」；`:100`（服务依赖走 `inject`，在清单声明接口和版本）。
- 说明：这些常量不是服务接口，也不是清单 `requires`，宿主无法解析版本、检查提供者是否存在或在提供者停止时传播降级。

### C-12 插件自报 manifest/能力，且版本号互相矛盾

- 证据：`M/EvUiPlugin.kt:121` 实现 `org.cordis.api.ManifestProvider`；`:597-606` 自建 `PluginManifest(id=..., version="0.1.0", capabilities=[...])`；而 `build.gradle.kts:15` `versionName = "1.1.0"`、`:109` sidecar `"version": "1.1.0"`；`M/ui/EvSettingsScreen.kt:205` 又显示「版本 0.1.0」。
- 违反：总规则 `:86`（登记全部在宿主）；`ev-manifest-spec.md:10`（清单固定为 `ev-plugin.json`）；`:23`「所有字段都是必需的……顶层出现未在本规范中定义的字段，直接拒绝（严格模式，不忽略未知字段）。」
- 说明：旧的入口类自报 manifest 不是新清单机制；三处版本号（1.1.0 / 1.1.0 / 0.1.0）不一致，宿主登记与回滚记录会出现歧义。

### C-13 旧分发与校验接线（bundle/plugin-verify/preBuild 任务）

- 证据：`build.gradle.kts:89-97` 抽 dex 打 jar；`:99-118` 生成 sidecar；`:120` `apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))`；`:124-126` `preBuild` 依赖另一插件的编译任务。
- 违反：总规则 `:107`「8. 旧仓库打只读标签，并附说明：基线哈希不存在、门禁依赖不存在的提交、内核为 SNAPSHOT。」；`ev-plan.md:166`「- 依赖方向检查：按第二节列出的允许依赖，任何越界依赖使构建失败。」
- 说明：旧校验脚本在本仓库不存在（`gradle/` 下只有 wrapper），旧构建无法按原样执行；新仓库的构建检查应落在依赖方向/契约/清单/内核门禁上，而不是旧插件容器的 verify 脚本。

### C-14 插件自行读写会话数据并依赖 `PLUGIN_STORAGE`

- 证据：`M/ChatSessionStore.kt:4` `org.cordis.api.PluginStorage`、`:28` `storage.read(...)`、`:48` `storage.atomicWrite(...)`；`M/ChatSessionDocuments.kt:22` 固定文件名 `sessions.json`；`M/EvUiPlugin.kt:111-119` 注释自述使用 `PLUGIN_STORAGE`、`:188-190` 在 apply 中迁移存储。
- 违反：总规则 `:44`「数据去留的规则：规则归宿主，数据内容归插件」；`ev-manifest-spec.md:100-101`（权限闭合集合为 `NETWORK`、`STORAGE_OWN`、`FILE_READ`、`FILE_WRITE`、`SHELL`、`CREDENTIAL_READ`）、`:102`「出现未知值，拒绝安装。」、`:104`「声明权限不等于获得权限。权限由宿主在裁决阶段统一申请、用户确认后才生效。」
- 说明：`PLUGIN_STORAGE` 不在新集合内；插件自己迁移数据、自己决定文件路径，绕开宿主的数据去留裁定。数据内容归插件不假，但访问权限必须由宿主裁决。

### C-15 在插件内实现宿主设置页/插件管理页，并触发权限授予

- 证据：`M/ui/EvSettingsScreen.kt:46-210` 完整设置页（外观、模型、工具、记忆、Linux、插件、宿主设置、HUD）；`M/ui/EvPluginCatalogScreen.kt:52-237` 插件管理页，`:157` `setEnabled`、`:164` `clearStorage`、`:183-184` `grantPermission`/`revokePermission`；`M/EvUiPlugin.kt:346-492` 把这些页面接进插件路由。
- 违反：`ev-plan.md:219`「入口页和插件管理页放在 app/，不放在 host-android。」；`:46-49`「**入口**（`host.entry`）- 只负责引导页和插件管理页的展示与触发。- 不持有业务状态……入口只依赖机制中的"查询"与"触发停止安全模式"两个接口」；`:137`「权限的授予和撤销只能由裁决层执行，插件不能请求自我授权。」
- 说明：旧插件把宿主入口/插件管理/授权开关做进了自己的页面；新规则要求这些在 `app/`，由宿主入口触发，权限授予由裁决层执行。

### C-16 直接依赖旧设计系统 `ui-kit` 与第三方 miuix 实现视觉

- 证据：`build.gradle.kts:48` `compileOnly(project(":ui-kit"))`；`ev.buan.ui` import 105 条（如 `M/EvChatColumn.kt:43-45`、`M/ui/EvChat.kt:40-43`）；`top.yukonga.miuix` import 22 条（如 `M/ui/EvDrawer.kt:51`、`M/ui/EvChat.kt:44-45`）。
- 违反：总规则 `:84`（插件只能依赖契约包）；`ev-plan.md:38`「- `plugins/*` → `host-api`」。
- 说明：`ui-kit` 不是 `host-api`，miuix 是第三方库；归档代码无法在新契约下直接编译。若保留视觉，只能提取颜色/圆角/间距/动效参数，由新宿主渲染层重写，不能保留这些依赖。

冲突计数：**16 条**。

## 6. 未确认清单

| 编号 | 未确认项 | 涉及文件:行号 | 需要什么信息才能判断 |
|---|---|---|---|
| U-01 | 旧清单的真实入口类 | `AndroidManifest.xml:5-6`（eta 包）vs `M/EvUiPlugin.kt:1`、`M/EvUiPlugin.kt:121`、`build.gradle.kts:110`（ev 包） | 旧仓库的历史提交或打包日志；`ev.buan.plugins.eta.ui.EtaUiPlugin` 是否曾存在，还是复制残留。 |
| U-02 | 旧构建校验脚本的内容 | `build.gradle.kts:120` | `gradle/plugin-verify.gradle.kts` 原文；本仓库没有该文件。 |
| U-03 | 旧设计令牌的具体数值 | 105 条 `ev.buan.ui.*` import，例如 `M/EvChatColumn.kt:43-45`、`M/ui/EvChat.kt:40-43` | `:ui-kit` 的 `EvThemeTokens`/`EvMotionTokens` 源码；本仓库 `settings.gradle.kts:13` 未 include `:ui-kit`。 |
| U-04 | 旧 agent 契约语义 | 45 条 `ev.buan.plugins.agent.api.*`，例如 `M/ApprovalGate.kt:6-7`、`M/ChatSessionController.kt:7-9` | `plugins/agent-api` 的接口与版本；本仓库无该模块（`settings.gradle.kts:13` 未 include，目录在审计时也不存在）。 |
| U-05 | 旧 `PluginStorage`/`ServiceDirectory`/loader 行为 | `M/ChatSessionStore.kt:4`、`M/ChatSessionController.kt:16`、`build.gradle.kts:49-50` | `:plugin-api`、`:loader` 源码；本仓库不存在，无法确认原子写、配额异常、服务查找与发布/撤销语义。 |
| U-06 | 代码引用的设计规范原文 | `M/ui/EvChat.kt:48-54`（`docs/design/EV-UI设计规范.md` §7.1/§7.2/§6.3）、`M/ui/EvApprovalDialog.kt:33-39`、`M/EvComposerArea.kt:55-57` | 该设计规范文件；归档与三份规则文档中均无此文件，无法核对 § 编号与具体数值。 |
| U-07 | 角色/技能编辑器是否仍使用 `EvLibraryParts` | `M/ui/EvLibraryParts.kt:31-40` 声称服务角色/技能编辑器；归档中没有对应屏幕文件 | 旧仓库完整目录或提交历史；确认调用方是否已迁移/删除，以判断该文件是活代码还是残留。 |
| U-08 | 角色 systemPrompt 的真实行为 | `M/ChatSessionController.kt:117-119` 注释称「carries the session's character as `systemPrompt`」，`:140` 实际传 `systemPrompt = null`；`ChatSession.characterId/skillId`（`M/ChatConversation.kt:22-23`）未在回合中使用 | 角色/技能库与 agent 契约；确认是未完成实现、临时禁用还是注释过期。 |
| U-09 | 版本号以哪个为准 | `build.gradle.kts:15` 1.1.0；`build.gradle.kts:109` 1.1.0；`M/EvUiPlugin.kt:600` 0.1.0；`M/ui/EvSettingsScreen.kt:205` 0.1.0 | 旧发布记录/标签；确认清单版本、构建版本与界面显示哪个是权威值。 |
| U-10 | Linux 页的 JSON 字段与工具契约 | `M/ui/EvLinuxScreen.kt:56`、`:64`、`:71-72`、`:80`、`:95-96`、`:187`（`ev.buan.plugins.linux`、`linux_status`、`linux_setup`、`installed/distribution/path/url`） | linux 插件的工具 schema 与设置键；本仓库无该插件。 |
| U-11 | `PLUGIN_STORAGE` 与新权限集合的映射 | `build.gradle.kts:113`、`AndroidManifest.xml:13`、`M/ChatSessionStore.kt:4` | 旧宿主权限表或 ADR-0003；三份规则文档只给新集合（含 `STORAGE_OWN`），未给旧名映射。 |
| U-12 | 7 个测试能否编译/通过 | `build.gradle.kts:64-79`；测试如 `T/ChatSessionControllerTest.kt:34-50`（Fake PluginStorage）、`T/ui/ModelProviderStateTest.kt:320-333`（Fake ModelProviderService） | 缺失的 `:plugin-api`、`:loader`、`:plugins:agent-api` 模块以及 `agentApiClasses` 文件；本次按要求未运行 gradle，无法给出执行结果。 |

未确认计数：**12 条**。

## 7. 引用自校验记录

写完后对关键行做了逐行回读（`grep -n` / `sed -n '<行号>p' <文件>`），并另用脚本核对了 §3.3 全部 182 条 import 的行号与符号；抽检结果如下：

| 引用 | 回读原文（截断） | 结果 |
|---|---|---|
| `docs/最高规则/ev-final-rules.md:84` | 「1. 插件只能依赖契约包。……」 | 一致 |
| `docs/最高规则/ev-final-rules.md:86` | 「3. 权限、信任、登记全部在宿主……」 | 一致 |
| `docs/规范/ev-plan.md:219` | 「3.入口页和插件管理页放在 app/……」 | 一致 |
| `docs/规范/ev-manifest-spec.md:102` | 「出现未知值，拒绝安装。」 | 一致 |
| `archive/ev-ui-legacy/build.gradle.kts:11` | `applicationId = "ev.buan.plugins.ev.ui"` | 一致 |
| `archive/ev-ui-legacy/build.gradle.kts:113` | `"permissions": ["PLUGIN_STORAGE"]` | 一致 |
| `archive/ev-ui-legacy/src/main/AndroidManifest.xml:6` | `android:value="ev.buan.plugins.eta.ui.EtaUiPlugin"` | 一致 |
| `M/EvUiPlugin.kt:121` | `class EvUiPlugin : Plugin<Unit>, AgentSurface, AgentPage , org.cordis.api.ManifestProvider {` | 一致 |
| `M/EvUiPlugin.kt:202` | `dir.publish(ApprovalService.Key, approvalGate)` | 一致 |
| `M/ApprovalGate.kt:69` | `if (decision == ApprovalDecision.ALLOW_SESSION) sessionAllowed += entry.prompt.toolName` | 一致 |
| `M/ChatSessionController.kt:130` | `val agent = directory?.lookup(AgentService.Key)` | 一致 |
| `M/ui/EvLinuxScreen.kt:64` | `val tool = reg?.lookup("linux_status")` | 一致 |
| `M/ui/EvPluginCatalogScreen.kt:183` | `if (on) manager?.grantPermission(manifest.id, permission)` | 一致 |
| `M/ChatSessionController.kt:140` | `systemPrompt = null,` | 一致 |

import 计数可复现：

```bash
find archive/ev-ui-legacy -name '*.kt' -print0 | xargs -0 grep -nH '^import ' | wc -l   # 831
grep -c '^import ' archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/*.kt \
  archive/ev-ui-legacy/src/main/kotlin/ev/buan/plugins/ev/ui/ui/*.kt                     # 逐文件计数
```

## 8. 附录：ui-kit 归档补充（并行任务，不计入 40 文件主统计）

> 范围说明：本节由并行的 ui-kit 任务追加，针对 `archive/ui-kit-legacy/` 的 24 个文件，不属于本任务 `archive/ev-ui-legacy/` 的 40 文件审计范围；其行号与计数由该任务提供，本审计未逐行复核。§9 的主统计仍只针对 40 个 ev-ui 文件。

旧 `ui-kit` 原位于 `plugins/ui-kit`；并行任务已按只读素材处理：**`plugins/ui-kit/` → `archive/ui-kit-legacy/`**（24 个文件，2,671 行），`archive/README.md` 与 `scripts/check-archive-isolation.sh` 已覆盖该目录，构建未引用（隔离检查 PASS）。

### 8.1 逐文件盘点（24 文件）

| # | 路径（相对 archive/ui-kit-legacy/） | 类型 | 行数 | 主要职责 | 处置 |
|---|---|---|---|---|---|
| 1 | build.gradle.kts | 构建脚本 | 42 | 旧 Android library：Compose 1.12.0 + miuix 0.9.4 + cloudy 玻璃库 + ap-snapshot 脚本 | 丢弃 |
| 2 | src/main/kotlin/ev/buan/ui/EvTokens.kt | Kotlin | 310 | **设计令牌源**：颜色（深浅）/字号/间距/圆角/弹簧参数 | 设计资产 |
| 3 | .../EvThemeSpec.kt | Kotlin | 67 | 主题规格：id/name/颜色构造/type/spacing/radii/glass/motion/elevation/icon | 设计资产 |
| 4 | .../EvStatusColors.kt | Kotlin | 29 | 状态色语义（success/warning/danger 来自令牌，running/idle 来自 Miuix） | 设计资产 |
| 5 | .../EvInsets.kt | Kotlin | 23 | 系统栏/刘海内边距约定（不含 IME） | 设计资产 |
| 6 | .../EvPageTransition.kt | Kotlin | 52 | 页面转场参数（TRAVEL=8、scale 0.985、reduced tween 120） | 设计资产 |
| 7 | .../EvAppearance.kt | Kotlin | 48 | 外观默认值/enums：主题模式、Monet、调色板、强调色、纯黑、模糊、缩放、reduced motion | 设计资产 |
| 8 | .../EvGlyphs.kt | Kotlin | 94 | 33 种字形目录（`EvGlyphKind`）与主题图标语言接口 | 设计资产 |
| 9 | .../EvSurface.kt | Kotlin | 215 | 玻璃/深度/按压/发丝线 Modifier 实现（cloudy 依赖） | 渲染参考 |
| 10 | .../EvTheme.kt | Kotlin | 133 | 主题组合：Miuix ThemeController + 令牌 CompositionLocal 注入 | 渲染参考 |
| 11 | .../EvGlyphVectors.kt | Kotlin | 455 | 手绘图标 vector path 数据 | 渲染参考 |
| 12 | .../EvPreferences.kt | Kotlin | 111 | 设置页偏好行/分组组件 | 渲染参考 |
| 13 | .../EvSettings.kt | Kotlin | 140 | 设置页组件（分区/行/尾部控件） | 渲染参考 |
| 14 | .../EvEmptyState.kt | Kotlin | 49 | 空态视觉组件 | 渲染参考 |
| 15 | .../EvDrawer.kt | Kotlin | 380 | 抽屉布局组件 | 外壳参考 |
| 16 | .../EvPage.kt | Kotlin | 61 | 页面容器/基础布局 | 外壳参考 |
| 17 | .../EvPageHeader.kt | Kotlin | 96 | 页面头部栏 | 外壳参考 |
| 18 | .../EvBackButton.kt | Kotlin | 22 | 返回按钮 | 外壳参考 |
| 19 | .../EvCard.kt | Kotlin | 35 | 分组卡表面（surface1 + medium radius，spec §7.5） | 外壳参考 |
| 20 | .../EvThemeRegistry.kt | Kotlin | 48 | 主题注册表（内建 + 外部注册/撤销） | 丢弃 |
| 21 | .../EvIcons.kt | Kotlin | 17 | 唯一暴露的返回图标（material icons） | 丢弃 |
| 22 | src/test/.../EvAppearanceTest.kt | 测试 | 55 | 外观归一化测试 | 丢弃 |
| 23 | src/test/.../EvThemeRegistryTest.kt | 测试 | 45 | 主题注册表测试 | 丢弃 |
| 24 | src/test/.../EvTokensTest.kt | 测试 | 144 | 令牌不变量测试（可参考断言） | 丢弃 |

处置统计（ui-kit）：**设计资产 7、渲染参考 6、外壳参考 5、丢弃 6**（合计 24）。
若与主审计合并则为 64 文件：设计资产 7、渲染参考 4+6=10、外壳参考 12+5=17、丢弃 24+6=30；本文件 §9 的主统计仍只针对 40 个 ev-ui 文件。

### 8.2 构建依赖（archive/ui-kit-legacy/build.gradle.kts）

| 坐标/声明 | 版本 | 行号 |
|---|---|---|
| `com.android.library` | 未声明（旧根工程） | :2 |
| `org.jetbrains.kotlin.plugin.compose` | 未声明 | :3 |
| compileSdk / minSdk | 37 / 33 | :8 / :10 |
| `androidx.compose.runtime:runtime`（api） | 1.12.0 | :24 |
| `androidx.compose.ui:ui`（api） | 1.12.0 | :25 |
| `androidx.compose.foundation:foundation`（api） | 1.12.0 | :26 |
| `androidx.compose.material:material-icons-core`（implementation） | 1.7.8 | :29 |
| `top.yukonga.miuix.kmp:miuix-ui-android`（api） | 0.9.4 | :30 |
| `top.yukonga.miuix.kmp:miuix-icons-android`（api） | 0.9.4 | :31 |
| `com.github.skydoves:cloudy`（implementation，alpha） | 1.0.0-alpha01 | :35 |
| `junit:junit` | 4.13.2 | :37 |
| `org.jetbrains.kotlin:kotlin-test-junit` | 2.4.20 | :38 |
| `apply(from = gradle/api-snapshot.gradle.kts)` | 旧脚本（不存在） | :42 |

### 8.3 import 分类（ui-kit）

- main 20 文件 245 条 import：Compose/Android/标准库 217；第三方 3 个库（`top.yukonga.miuix`、`com.skydoves`、`kotlinx.coroutines`）；**宿主相关 import 0 条**（ui-kit 本身就是旧宿主设计系统，不反向依赖 ev-ui/ agent）。
- test 3 文件 16 条 import：全部 Compose/Android/标准库。

### 8.4 冲突/差异清单（ui-kit，4 条）

| # | 冲突/差异 | 证据 | 违反/说明 |
|---|---|---|---|
| U1 | 旧设计系统以 Android library + Compose/Miuix 组件形式存在；新宿主规则要求"宿主只做机制和裁决，不做能力"以外的默认主题由宿主拥有，但不得把旧实现直接搬入 | `build.gradle.kts:1-42`；`EvTheme.kt:16-20` | 总规则 :7（宿主机制/裁决）、:24-26（入口归宿主）；只可提取令牌，不可复制代码 |
| U2 | ui-kit 依赖 miuix 0.9.4 + cloudy 1.0.0-alpha01（alpha）；新宿主是否接受 miuix 作为默认组件库未决 | `build.gradle.kts:30-31,35` | 依赖方向/产品依赖需人工决定；probe 已证明 miuix 可在 Kotlin 2.4.21 下编译 |
| U3 | compileSdk 37 / minSdk 33 与主工程 35 / 26 不一致 | `build.gradle.kts:8,10`；app:8,12 | 旧素材仅参考；主工程保持 35/26 |
| U4 | 旧规范 `docs/design/EV-UI设计规范.md` 不在归档，令牌注释多次引用其章节 | `EvTokens.kt:24-28` 等 | 设计原则原文仍缺，无法核对（未确认，非代码冲突） |

### 8.5 补充结论

- 设计令牌**已可完整提取**（见 `ev-design-assets.md`）：颜色 28 值、字号 7 级、间距 7 档、圆角 3+2、动效 7 参数、玻璃/层级 7 参数等。
- 旧插件内联值与令牌存在 4 组数值冲突/差异（强调色、圆角、间距、字号），已在设计资产 §6 标注「待人工裁决」。
- ui-kit 代码本身（Compose/Miuix/cloudy 实现）不得复制进产品代码；仅令牌与原则可作为设计资产依据。
- 仍缺：旧设计规范原文（设计原则/章节值无法核对）。

## 9. 统计摘要

- 文件总数：**40**（31 个 main `.kt`、7 个 test `.kt`、1 个 `build.gradle.kts`、1 个 `AndroidManifest.xml`）。
- `.kt` 文件：38；测试文件：**7**。
- 总行数：可见行 **9308**；`wc -l` **9307**（仅 `EvChatColumn.kt` 末尾无换行）。
- import：**831** 条（主代码 772、测试 59）。
- 处置：设计资产 **0**、渲染参考 **4**、外壳参考 **12**、丢弃 **24**，合计 **40**。
- 冲突：**16** 条。
- 未确认：**12** 条。
- 外部模块缺失：`:ui-kit`、`:plugin-api`、`:loader`、`plugins/agent-api` 四个；另 `gradle/plugin-verify.gradle.kts` 缺失。
- 本次未运行 gradle（遵守任务约束），因此「不可编译/测试不通过」是从模块缺失与 import 得出的静态结论，不是执行结果。
- 范围外：§8 附录另列 `archive/ui-kit-legacy/` 24 个文件（并行任务），不计入上面的 40 文件与处置计数。

