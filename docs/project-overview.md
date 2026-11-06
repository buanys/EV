# EV 项目现状（交接档案）

> **最后更新：2026-11-06（重构 4e 收尾）**
> 这份文档写给第一次接触本项目的人，也写给三个月后的自己。读完它，你应该能不看代码就说清楚：
> EV 是什么、仓库怎么切、哪些事已经做完、哪些还是空的、改完之后怎么验。
>
> 文档纪律：**这里只写当前事实**。历史决策去 `adr/`，规则去 `newdocs/最高规则/`，
> 讨论中的方案去 `newdocs/草案/`。`architecture.md` 与根 `README.md` 的部分内容已经过时，
> 以本文为准（见 §11）。

---

## 1. 一句话

EV 是一个跑在 **root 安卓机**上的**插件化宿主 app**（包名 `ev.buan`）。插件是 jar/APK，
扔进 `/storage/emulated/0/EV/mods` 就能装；主线产品是**一个能动手操作手机的 agent**——
它能读屏、点按、输入、跑命令、抓网页，也能在手机上给自己编译并安装新插件。

三个事实决定了它所有设计：

1. **插件跑在宿主进程里**，同 UID、能调 `su`。所以"权限"是声明加旁路，不是沙箱（ADR-0003）。
2. **宿主必须能独立工作**。没有插件时它仍要能启动、能进安全模式、能打开管理页。
3. **内核是从上游 vendor 来的**，一行不改，改内核要回上游。

---

## 2. 仓库地图（真实目录）

```
third_party/cordis/          内核 vendor：core + loader（哈希锁住，见 §7）
host-api/                    契约：插件能看见的全部类型（org.cordis.api + ev.buan.plugins.agent.api）
host-core/                   宿主纯 JVM 逻辑：校验、权限元数据、依赖解析、安装判定、存储布局
host-runtime/                宿主私有实现：装配、激活、安装、预算、离屏渲染、路由（插件不可见）
host-app/                    仅 Android 入口：Application、4 个 Activity、3 个 Service、启动动画
ui-kit/                      设计系统 ev.buan.ui（宿主与插件共用同一份类）
plugins/base/        7 个     能力提供者：工具、存储、日志、设备、屏幕、shell、Linux 环境
plugins/feature/     5 个     页面与行为：主界面、对话大脑、浏览器、提示词、设置
plugins/integration/ 4 个     对接外部：模型服务、网页搜索、forge 编译与安装
plugins/resource/    1 个     数据与主题：默认主题
packages/ packager/          内核配套：插件包格式、Gradle 打包插件
scripts/                     三条门禁 + plugin.sh（构建部署）+ forge 载荷准备
docs/                        文档（见 §11）
```

**已经不存在的**：`host/`、`plugin-api/`、`demo/`、`utils/`、`timer/`、`logger-console/`、`hmr/`、`include/`。
根 `README.md` 和 `docs/architecture.md` 里提到它们的地方是历史遗留。

**两个只剩余 build 产物的空目录**：`plugins/agent-api/`、`plugins/hello/`。源码已删、`settings.gradle.kts`
已摘除，只剩编译缓存。可以安全 `rm -rf`。

---

## 3. 现在跑到哪了

### 规模（2026-11-06 实测）

| | |
|---|---|
| 提交 | 231 次 |
| Kotlin | 379 文件 / 50.7k 行（不含 vendored 内核） |
| 其中主代码 / 测试 | 297 / 75 文件 |
| 测试用例 | **755 个，0 失败**（98 个套件） |
| 文档 | 11 篇 ADR + 35 篇文档 / 6k 行 |
| 设备 | EV 0.1.0，17 个插件共 27.2 MB |

### 六阶段重构

| 阶段 | 状态 | 做了什么 |
|---|---|---|
| 0 立规矩 | ✅ | tag `pre-refactor`、ADR-0001、三条门禁 |
| 1 契约收口 | ✅ | `plugin-api` → `host-api`；删掉 `agent-api` 空壳插件（它靠一个空容器把契约 dex 塞进别人 classpath） |
| 2 宿主拆分 | ✅ | `HostRuntime` → `HostComposition`；拆出 Routing / Ops / Recovery / State；host-app 与 host-runtime 分家 |
| 3 插件四类归位 | ✅ | 插件搬进 `plugins/<类>/`；mods 从 84 MB 减到 27.2 MB |
| 4 拆业务枢纽 | ✅ 基本完成 | 见下 |
| 5a 内存压力 | ✅ | `onTrimMemory` 时取消在飞的离屏渲染 |
| 5b 执行预算 | ✅ | `RuntimeBudgetService`：按档位限并发、拒绝而非排队 |
| 5c 隔离档位 | ⬜ **未做** | 信任三档 + 子进程 + broker |

### 4e 拆白名单：14 → 1

架构门禁的白名单是"待办清单"，每条都是曾经超 400 行的文件。本轮把它们逐个拆掉：

| 原文件 | 现在 |
|---|---|
| `HostComposition` 978 | **666** + `HostActivation` 330 + `HostInventory` 106 + `HostInstallService` 144 |
| `PluginInstaller` 689 | 388 + `PluginMetadataReader` 138 + `PluginFiles` 176 + `PluginCollector` 84 + `PluginPolicy` 33 |
| `PluginsActivity` 720 | 373 + `PluginCardView` 279 + `HostFallbacks` 118 |
| `EvUiPlugin` 642 | 386 + `EvFullPage` 182 + `EvApprovalRoot` 27 + `EvUiManifest` 24 + `EvPluginPage` 51 + `EvChatCopy` 29 |
| `EvDrawer` 533 | 157 + `EvDrawerChrome` 230 + `EvDrawerHistory` 205 |
| `AgentBrainService` 522 | 399 + `BrainSessionHistory` 83 + `BrainWrapUp` 71 |
| `EvComposer` 519 | 368 + `EvComposerKeys` 180 |
| `EvGlyphVectors` 455 | 81 + `EvGlyphControls` 134 + `EvGlyphSubjects` 268 |
| `EvMarkdown` 438 | 233 + `EvMarkdownParser` 223 |
| `EvPluginCatalogScreen` 409 | 317 + `EvPluginSettingDialog` 113 |
| `PaperStartup` 402 | 317 + `PaperParts` 123 |

**唯一剩下的白名单条目是 `HostComposition` 666 行**。它已经不是上帝类——除了 `start()`（131 行，
其中约 100 行是"把宿主服务一个个放上 Cordis 总线"），文件里没有任何一块超过 15 行，剩下的
是四十个 1–5 行的转发与 getter。再拆只能拆到 486 行左右，换不来质变；详见 §8 的判断。

---

## 4. 宿主对外提供了什么

插件的全部能力来自 `host-api`。按用途分三组：

**平台类（宿主独占，插件拿不到 Context）**

| 契约 | 作用 |
|---|---|
| `ScreenService` | 读屏、点按、输入、滑动、按键（无障碍，需用户开启） |
| `PageReader` | 离屏 WebView 渲染取正文（插件抓不到 SPA） |
| `DeviceContextService` | 设备状态、应用列表与启动、剪贴板、通知 |
| `HudService` | 屏幕上的悬浮状态胶囊 |
| `BackgroundService` | 前台常驻服务 + 通知 |
| `RuntimeBudgetService` | 执行预算：能不能开、多久必须结束 |

**插件管理类（宿主裁决）**

| 契约 | 作用 |
|---|---|
| `PluginManagerService` / `PluginPageService` | 启停插件、取别人的页面 |
| `PluginInstallService` | 装/卸容器（forge 走这条路） |
| `ServiceDirectory` | 插件之间在运行时查表找服务 |
| `PermissionService` | 自己有哪些授权 |
| `PluginStorage` / `UserDataStorage` | 插件区 / 用户数据区（后者卸载不删） |
| `AppearanceService` / `ThemeCatalogService` | 外观与主题目录 |
| `HostNavigation` / `HostRestart` | 打开宿主页面 / 重启进程 |

**agent 契约（`ev.buan.plugins.agent.api`）**：`AgentService`、`ToolRegistry`、`ModelService`、
`MemoryService`、`SessionLog`、`SystemPromptService`、`ApprovalService`、`HudState` 等 25 个文件。
它们是**插件之间的**契约，由宿主代发（走共享包），不是宿主服务。

---

## 5. 十七个插件

| 目录 | id | 做什么 | inject（宿主服务） |
|---|---|---|---|
| base/agent-tools | …agent.tools | 工具总线（`ToolRegistry` 的实现） | ServiceDirectory, PluginStorage |
| base/agent-device | …agent.device | 共享存储文件读写、应用/设备/剪贴板/通知工具，15 个工具 | — |
| base/agent-gui | …agent.gui | 读屏点按的 11 个工具 | — |
| base/agent-shell | …agent.shell | `run_shell`（root，走审批）+ shell 审批策略与自我保护 guard | ServiceDirectory |
| base/agent-log | …agent.log | 会话事件流（append-only + 回放） | PluginStorage, ServiceDirectory |
| base/agent-memory | …agent.memory | 三层记忆：常驻 / 记事本（可检索）/ 蒸馏 | ServiceDirectory（多行声明） |
| base/linux | …linux | chroot Debian 环境 | ServiceDirectory, PluginStorage |
| feature/ev-ui | …ev.ui | 主界面：对话、抽屉、快捷菜单、设置页、页面栈 | AppearanceService 等 |
| feature/agent-brain | …agent.brain | 一轮对话的编排：工具轮、审批、预算、收尾 | ServiceDirectory, HudService |
| feature/agent-browse | …agent.browse | `web_search` / `web_fetch` + 浏览器面板 | ServiceDirectory, RuntimeBudgetService |
| feature/agent-prompt | …agent.prompt | 系统提示词（分层拼装） | ServiceDirectory |
| feature/settings | …settings | 设置页（宿主的，不是 ev-ui 里的） | AppearanceService |
| integration/agent-model | …agent.model | 模型 Provider（OpenAI 兼容，多模型） | ServiceDirectory（多行） |
| integration/agent-web | …agent.web | 网页搜索后端 | ServiceDirectory, PluginStorage |
| integration/forge | …forge | 设备端编译安装插件（`forge_build` / `forge_install`） | ServiceDirectory |
| integration/forge-compiler | …forge.compiler | K2 编译器 + D8 载荷（26 MB，默认关闭） | ServiceDirectory |
| resource/theme-default | …theme.default | 默认主题 | ServiceDirectory |

**两点要注意**：

- **没有一个插件 inject 另一个插件**。插件之间全是运行时 `directory.lookup(...)` 动态发现。
  sidecar 里的 `dependencies` 只用来排加载顺序，**没有版本、没有接口、宿主装配时不检查**。
- 四类划分**只活在目录名里**。代码中没有任何按类分支的逻辑（`PluginCategory` 这种东西不存在）。
  规则给每类赋予的差异（基础=随包内置、资源=叶子、集成=凭据归宿主）一条都没实现。

---

## 6. 三条门禁

```sh
sh scripts/check.sh     # CI 与提交前都跑这一个
```

| 门禁 | 检查什么 | 为什么 |
|---|---|---|
| `verify-kernel-lock.sh` | `third_party/cordis` 目录树哈希 == `KERNEL.lock` | 内核冻结，改内核必须回上游 |
| `verify-module-boundaries.sh` | 插件不依赖宿主内部模块，宿主不依赖插件 | 依赖方向单向 |
| `verify-file-size.sh` | 主代码单文件 ≤ 400 行，白名单只减不增 | 超过 400 行的文件通常开始同时回答好几个问题 |

白名单 `scripts/architecture-allowlist.txt` 是**待办清单**，不是豁免。新文件超限直接失败，
不允许加进去。

---

## 7. 内核与共享边界

- 内核固定在 `third_party/cordis/`，上游 `cordis-kotlin` 的 `7b13e6b`，只含 `core` + `loader`。
- 插件类加载器 `IsolatedPluginClassLoader` 的兜底是 **host 的 parent**（Android 上是
  `BootClassLoader`），所以插件**加载不到** `ev.buan.HostComposition` 这类宿主内部类。
- 共享包（`gradle/shared-packages.txt` + `HostPackages.kt` + `proguard-rules.pro`，**三处必须同步**）：
  Compose、miuix、okhttp、Room、jsoup、`ev.buan.ui`、`ev.buan.plugins.agent.api`。
- 内建前缀（loader 自带，不用列）：`java`、`javax`、`android`、`kotlin`、`org.cordis`。

**结论**：插件 → 宿主内部 这条边在构建期（门禁）和运行期（类加载器）都封住了。
插件 ↔ 插件 这条边是松的，见 §8。

---

## 8. 明确没做的（诚实清单）

按风险排序，不是按工作量。

### 8.1 信任三档不存在 —— 最大结构缺口

规则要求：可信（随包内置+签名）→ 同进程；受限（用户确认的第三方）→ **子进程 + broker**；
不可信（来源不明）→ 默认拒绝。

实际：只有"签名 / 未签名"两态（`autoTrustUnsigned = false`，未签名逐个确认）。所有插件
同进程、同 UID、能调 `su`。这就是 5c，一行没做。

### 8.2 凭据归宿主 —— 已做（2026-11-06）

`CredentialService`（host-api）+ `CredentialStore`（host-runtime）+ `HostPermission.SECRETS`。
三道门：**声明**（没写 `SECRETS` 的插件拿不到绑定）、**授权**（没批就每次调用抛
`SecurityException`，补授权不用重启）、**归属**（每个实例只认自己的 pluginId）。

`agent-model` 是第一个用户：provider 的密钥不再写进 `providers.json`，改存宿主
`files/secrets/<pluginId>.apk.json`（权限 600）。旧文件里带的 key 在第一次 `load()` 时搬过去
并重写文档——真机上验证过：`providers.json` 里 `apiKey` 计数归零，模型仍能正常调用（界面显示
"已配置 Key"）。

**不是加密**：app 私有目录已经是同 UID 之外读不到，这一层的价值是"换个柜子 + 一把钥匙"，
不是保险箱。用 Keystore 加密是后续可选项。也**不代理网络**——插件拿到明文仍自己发 HTTP，
真正的隔离要等 5c 的 broker。

⚠️ 已有安装升级后，需要在管理页给 agent-model 补授权「凭据保管」，否则它会停在
`PERMISSION_DENIED: 缺少权限 SECRETS`（卡片会写明原因，授予后自动启用）。

### 8.3 依赖版本 —— 已做（2026-11-06）

sidecar / APK 元数据的 `dependencies` 现在支持 `id@1.2.0`，语义是**最低版本**。
`PluginVersion`（语义化三段比较）+ `DependencyCheck`（装配检查）在 `:host-core`，
单测覆盖解析、比较、缺失与版本不够两种失败、以及"写坏的版本号"。

`PluginValidator` 新增 `DEPENDENCY_VERSION` 错误码：缺插件报 `DEPENDENCY_MISSING`，
装了但太旧报 `DEPENDENCY_VERSION`——**两种失败的处置不同**（一个去装，一个去升级）。

`PluginCandidate.dependencies` 保留 `id@version` 交给校验器；`AndroidModuleDescriptor.dependencies`
剥成纯 id（内核只认 id，带版本会变成"模块未注册"）。这两处一开始我搞反了，
真机上表现为 agent-browse 激活失败。

### 8.4 恢复只做了两项

| 恢复能力 | 状态 |
|---|---|
| 安全模式（崩溃标记 → 不加载任何插件） | ✅ |
| 崩溃记录（插件、版本、阶段） | ✅ |
| 失败计分（崩溃/超时累计扣分、超阈值停用） | ❌ 没有 |
| 回滚到上一个可用版本 | ❌ 没有 |

### 8.5 事件总线：机制装了，没人用

内核自带事件总线。`Events.parallel` 对监听器异常有保护（收集成 `AggregateEventException`），
`Events.emit` 是裸调用（异常直接抛回发送方）。

但**宿主和 17 个插件都没有订阅任何内核事件**——全仓 grep 零订阅、零 emit。插件之间是靠
`ServiceDirectory` 查表加直接方法调用组合的。

所以最高规则里"内核的事件总线对监听器异常没有保护，宿主要自己包一层事件分派"这一条，
针对的是一个当前不存在的用法。它不是"欠了一层实现"，而是"机制装了但没用"。
真要用事件总线之前先决定：要么宿主包一层，要么把这句规则改掉。

### 8.6 三件只有单测证据、没上过真机的事

1. **5a 内存压力**：要系统发 `onTrimMemory` 才触发，没演练过。
2. **5b 执行预算**：要真实并发把某档打满才触发拒绝，没触发过。
3. **卸载路径的删除**：`removeExternal` / `removePrivate` 本轮搬进 `PluginFiles`，
   要牺牲一个插件才能测，没测。

---

## 9. 怎么改、怎么验

### 构建

环境在 Linux chroot（Debian）里，不在 Android 侧：

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-9.7.1/bin/gradle assembleDebug --console=plain      # 宿主 APK
/opt/gradle-9.7.1/bin/gradle test --console=plain               # 全部单测
sh scripts/check.sh                                             # 三条门禁
```

### 插件

```sh
sh scripts/plugin.sh build ev-ui        # → plugins/feature/ev-ui/build/dist/ev-ui.jar
```

⚠️ **`bundlePlugin` 会假绿**：源文件改了它有时仍复用旧 jar（sha256 一模一样）。
部署前必须看时间戳，或者加 `--rerun-tasks`。

### 部署与验证（Android 侧，root）

```sh
cp plugins/feature/ev-ui/build/dist/ev-ui.jar /sdcard/EV/mods/
pm install -r host-app/build/outputs/apk/debug/host-app-debug.apk
am force-stop ev.buan; am start -n ev.buan/.MainActivity
logcat -d -s EV:V | grep -c "plugin accepted"     # 期望 17
```

**"17/17 accepted" 是唯一可信的启动健康指标**。首页的"45 个工具 · 记忆已启用"是第二道。

### 调试包自动信任未签名插件

`FLAG_DEBUGGABLE` 的包不需要手动信任；release 包逐个确认（按 id + sha256）。

---

## 10. 踩过的坑（操作经验，最容易忘）

按发现顺序，每条都有代价。

1. **移动 public 顶层函数所在文件 = 改 ABI**。Kotlin 顶层函数的 JVM 类名由文件名决定：
   把 `evThemeGlyph` 从 `EvGlyphVectors.kt` 挪到 `EvGlyphLanguage.kt`，编译、单测、门禁、
   apiSnapshot 全绿，真机上 `theme.default` 插件激活失败（`NoSuchMethodError`）。
   插件是独立编译的 jar，链接的是旧类名。**internal 的随便移，public 的必须保留文件名。**
2. **新文件名先查存在性**。我曾把 `EvChatChrome.kt` 当新名字覆盖，那里面住着两个已在用的
   Composable。
3. **`bundlePlugin` 假绿**（见 §9）。
4. **按结构切代码，先打印再切**。用花括号计数切函数，遇到"签名跨行 + 体内有 `{`"会只切走
   函数头；遇到 `= HostState(...)` 这种表达式体函数会只删一行。两次都是先跑脚本后看结果，
   文件被我搞坏过两回。
5. **插在链式调用中间会断链**。`private val scanCache = X(...)\n    .also { it.load() }`，
   往中间插新字段，`.also` 就挂到新字段上了——编译能过，scanCache 从此不 load。
6. **装配链里的 `error(...)` 会连坐**。一个服务没提供，后面的服务全部不发布，
   表象是"没有主页插件"。
7. **回滚代码 ≠ 回滚设备**。判断回归前必须先重装 APK。
8. **Gradle `UP-TO-DATE` 是假绿**，改完要 `--rerun-tasks`。
9. **Kotlin 块注释可嵌套**，多写一行 `/**` 会吞掉后面的声明，报错指向下游。
10. **跨模块 public 属性不能 smart cast**，先绑局部变量。
11. **加新 Android 模块要改根 `build.gradle.kts` 的 `kernelModules` 过滤器**，否则 KMP 与
    android-library 冲突。

---

## 11. 文档地图

| 想知道 | 看这里 | 状态 |
|---|---|---|
| 项目现状（本文） | `docs/project-overview.md` | ✅ 最新 |
| 最高规则（验收标准） | `docs/newdocs/最高规则/ev-final-rules.md` | ✅ 权威，但 §二 的部分条目未落地（见 §8） |
| 为什么这么定 | `docs/adr/` 11 篇 | ✅ 每篇只记一个决策 |
| 内核冻结章程 | `docs/kernel-freeze.md` | ✅ |
| UI 设计规范 | `docs/design/EV-UI设计规范.md` | ⚠️ v1.0 草案，作者说过"不一定正确" |
| 设计令牌提取 | `docs/DESIGN-EXTRACT.md` | ✅ |
| 设备端编译原理 | `docs/research/on-device-compiler.md` | ✅ 已实测 |
| 架构总览（四层） | `docs/architecture.md` | ⚠️ **部分过时**：还说"插件三类"、`demo/` 冻结、`hmr` 在用 |
| 仓库介绍 | 根 `README.md` | ⚠️ **过时**：说 `:host`（现 `:host-app`）、`hmr/include` 在冻结阵营 |
| 讨论中的方案 | `docs/newdocs/草案/` | ⚠️ 草案，不作数 |
| 历史工作记录 | `docs/archive/` | 只读，**不作现状依据** |

---

## 12. 下一步（按优先级）

1. **凭据归宿主**：`CredentialService` 发给所有插件，密钥从插件配置搬到宿主区，
   与"集成类"无关。改动小、直接违反规则、有安全含义。
2. **依赖版本**：`Dependencies` 加版本，是"装配检查"和"替换"的前提。
3. **事件总线：先决定用不用**。现在全仓零订阅，规则那句话针对的用法还不存在——
   要么改用事件，要么把规则那句改掉，别留一条永远"未完成"的条目。
4. **失败计分 + 回滚**：恢复章缺的两项。
5. **5c 信任档位**：最大的一块，也是规则里最硬的要求。

**不建议现在做的**：继续拆 `HostComposition`（见 §3 末尾的判断）、给四类加"机制"——
宿主看不到也不该看到这个分类，它只该看**来源**（签名 / 存储位置）和**声明**（权限 / 依赖 /
提供服务）。这条需要一条 ADR 把规则里的张力钉下来。
