# 宿主能力面：服务与权限

> **这份文档是唯一权威来源（single source of truth），记录宿主当前真实实现的服务和权限。**
> 不是设计愿景，不是规范草案——只写代码里已经成立的事实。
>
> - 建立：2026-11-10，对应提交「step 1：宿主能力面归档 + 发布坐标 + 版本门」
> - 读者：写 `ev-*` 插件的人、以及**未来由 AI 自己写插件时的检索入口**
> - 维护规则见文末 §7。**改了宿主服务不更新本文档，视为漏改。**

## 0. 一句话结论

宿主 = **40 条 Android 运行时权限**（`host-app/AndroidManifest.xml`）
＋ **40 个服务 Key**（`host-api` 的 `ServiceKey` 声明）
＋ **15 个 `HostPermission`**（`host-api/.../HostPermission.kt`）。

关键在于：**这三者没有一一对应**。40 条 Android 权限里，只有一部分被包装成宿主服务，
再交给插件；剩下的躺在 manifest 里，插件碰不到。这正是"宿主偏科"的准确含义。

---

## 1. 服务怎么送到插件手上

宿主有四种送达方式，**决定一个插件能不能拿到某个服务**，必须先分清：

| 送达方式 | 机制 | 插件怎么拿 | 有无权限门 |
|---|---|---|---|
| **A 根上下文** | `cordis.provide(Key, …)` | `ctx.require(Key)` / `ctx[Key]` | 无 |
| **B 服务目录** | `ServiceDirectory.publish(Key, …)` | `directory.lookup(Key)` | 无 |
| **C 声明下发** | `provide*IfDeclared(...)` | 声明了 `HostPermission` 且用户授权才有 | **有** |
| **D 插件互供** | 插件在 `apply` 里 `publish` | 同上，或硬注入 | 无（靠信任档位） |

> A 和 B 的区别很实际：A 是**强依赖**，插件 `inject` 里写了它，缺失时插件装不上；
> B 是**可选查询**，`lookup` 返回 null 也不会让插件启动失败。
> 这就是 `ServiceDirectory` 存在的理由——内核的强注入会让消费者一直等。

---

## 2. A｜根上下文（全员可见，无权限门）

宿主在 `HostComposition` / `HostActivation` 里 `cordis.provide`，**任何插件都能拿到**。

| Key | 接口 | 主要能力 | 实现 |
|---|---|---|---|
| `host/appearance` | `AppearanceService` | 改宿主外观（`update { }` 变换） | `HostComposition` |
| `host/navigation` | `HostNavigation` | 打开插件管理页、设置页 | `HostComposition` |
| `host/page-reader` | `PageReader` | 把插件页面渲染成可读文本（给模型看） | `HostComposition` |
| `host/service-directory` | `ServiceDirectory` | 插件间服务发布 / 撤销 / 查询 | `HostComposition` |
| `host/plugin-install` | `PluginInstallService` | 装 / 卸插件、申请重启 | `HostComposition` |
| `host/device-context` | `DeviceContextService` | 应用列表与启动、设备状态、剪贴板、发通知、读通知 | `DeviceContextServiceImpl` |
| `host/screen` | `ScreenService` | 读 UI 树、点击、点击文字、输入、滑动、按键 | `ScreenServiceImpl` |
| `host/hud` | `HudService` | 悬浮胶囊显示状态、脉冲、挂控制器 | `HudServiceImpl` |
| `host/restart` | `HostRestart` | 重启宿主进程 | `HostRestartImpl` |
| `host/permission` | `PermissionService` | `isGranted` / `require`（查的是 `HostPermission`） | `HostActivation` |
| `host/background` | `BackgroundService` | 保活引用计数（`acquire` / `release`） | `EvBackgroundService` |
| `host/runtime-budget` | `RuntimeBudgetService` | 读各插件运行预算用量 | `HostActivation` |

**注意 `host/device-context`**：它把 `clipboard()`、`setClipboard()`、`notify()`、
`notifications()` 全给了，**而且不需要任何 `HostPermission`**。
`HostPermission` 的 KDoc 里说 `CLIPBOARD` / `NOTIFICATIONS` 是"没有服务的保留词"——
**这句话已经过期**，第 4 节会纠正。

---

## 3. B / C｜目录发布与声明下发

### 3.1 ServiceDirectory 发布（需 `lookup`）

| Key | 接口 | 主要能力 | 实现 |
|---|---|---|---|
| `host/theme-catalog` | `ThemeCatalogService` | 列出 / 解析主题 | `HostThemeCatalog` |
| `host/plugin-catalog` | `PluginCatalogService` | 列出插件 manifest、读写插件设置 | `HostComposition` |
| `host/plugin-manager` | `PluginManagerService` | 列插件、启用/停用、授权/撤权、清存储 | `HostComposition` |
| `host/plugin-page` | `PluginPageService` | 按插件 id 取页面 | `HostComposition` |
| `host/ui-slots` | `UiSlotRegistry` | 宿主界面里预留的孔：6 个具名槽位，可替换或累加 | `UiSlotRegistryImpl` |

`host/page-reader` 是**双通道**：既在根上下文，也在目录里。

### 3.2 声明下发（`provide*IfDeclared` 闸门）

这是宿主**唯一真正在意权限**的一组。规则统一：**manifest 里声明了 `HostPermission`
且用户授权，才有绑定；否则 `ctx[Key]` 是 null，连服务实例都不存在。**

| Key | 接口 | 门控权限 | 实现 |
|---|---|---|---|
| `host/storage` | `PluginStorage` | `PLUGIN_STORAGE` | `PluginStorageImpl` |
| `host/userdata` | `UserDataStorage` | `PLUGIN_STORAGE` | `PluginStorageImpl` |
| `host/credential` | `CredentialService` | `SECRETS` | `CredentialServiceImpl` |
| `host/calendar` | `CalendarService` | `CALENDAR` | `CalendarServiceImpl` |
| `host/device-control` | `DeviceControlService` | `DEVICE_CONTROL` | `DeviceControlServiceImpl` |
| `host/browser` | `BrowserService` | `BROWSER` | `BrowserProvision` |
| `host/contacts` | `ContactsService` | `CONTACTS` | `ContactsServiceImpl` |
| `host/sms` | `SmsService` | `SMS` | `SmsServiceImpl` |
| `host/media` | `MediaService` | `MEDIA` | `MediaServiceImpl` |
| `host/location` | `LocationService` | `LOCATION` | `LocationServiceImpl` |

其中 `SECRETS`、`CALENDAR`、`DEVICE_CONTROL`、`BROWSER` 还有**调用时二次检查**：
声明了但用户撤权，每次调用抛 `SecurityException`，不是静默降级。

---

## 4. D｜由插件实现的服务（agent 域）

这些 Key 的**实现方是插件，不是宿主**。宿主只负责发现与注入。
写正式版插件时，你就是这些服务的提供者。

> **当前状态（2026-11-10）**：下表的实现方**一个都还不在源码树里**。
> 实验版 17 个插件已整体归档到 `/workspace/EV-archive/2026-11-10-experimental-plugins/`，
> 正式版从 `ev-tools` 开始重写。所以**现在从源码构建出来的宿主是个空壳**：
> 能启动、能装插件，但没有 agent——这些服务没有任何发布者。
> 表里的名字是"正式版计划由谁实现"，不是"已经实现"。

| Key | 接口 | 由谁实现 | 消费方 |
|---|---|---|---|
| `agent/service` | `AgentService` | `ev-brain` | `ev-ui` |
| `agent/tool-registry` | `ToolRegistry` | `ev-tools` | 15 处（几乎所有能力插件） |
| `agent/tool-guards` | `ToolGuardRegistry` | `ev-brain` | brain 自身 |
| `agent/approval` | `ApprovalService` | `ev-ui` | brain（dangerous 工具前置） |
| `agent/approval/policy` | `ApprovalPolicy` | `ev-shell` | brain |
| `agent/model` | `ModelService` | `ev-model` | brain |
| `agent/model-providers` | `ModelProviderService` | `ev-model` | ev-ui |
| `agent/memory` | `MemoryService` | `ev-memory` | brain |
| `agent/memory-store` | `MemoryStoreService` | `ev-memory` | brain |
| `agent/session-log` | `SessionLog` | `ev-log` | brain |
| `agent/system-prompt` | `SystemPromptService` | `ev-prompt` | brain |
| `host/theme-provider` | `ThemeService` | `ev-theme-*` | 宿主 + ev-ui |
| `host/hud-renderer` | `HudRenderer` | HUD 渲染插件 | `HudServiceImpl` |

> **一处异常，已记录待查**：`agent/approval/policy` 当前由 `agent-shell` 发布，
> 而不是由 ev-ui 或 brain。这是实验期的历史遗留，正式版应当归位到审批相关插件。

---

## 5. 权限全表

15 个 `HostPermission`，**按真实执行力分三档**。这分档是 ADR-0003 承认的现实，
不是我们的疏忽——插件与宿主同进程同 UID，枚举拦不住直接的 Android 调用。

| 权限 | 门控的服务 | 相关 Android 权限（宿主持有） | 执行力 |
|---|---|---|---|
| `PLUGIN_STORAGE` | `PluginStorage`、`UserDataStorage` | 无（宿主私有目录） | ✅ **真拦**：未授权时绑定不存在 |
| `BACKGROUND` | `BackgroundService` | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS` | ✅ **真拦**：`acquire` 抛 `SecurityException` |
| `SECRETS` | `CredentialService` | 无 | ✅ **真拦**：无绑定 + 每次调用重查 |
| `CALENDAR` | `CalendarService` | `READ_CALENDAR`、`WRITE_CALENDAR` | ✅ **真拦**：同上 |
| `DEVICE_CONTROL` | `DeviceControlService` | `CHANGE_WIFI_STATE`、`CHANGE_NETWORK_STATE`、`BLUETOOTH_CONNECT`、`ACCESS_NOTIFICATION_POLICY`、`CAMERA`（手电筒） | ✅ **真拦**：同上 |
| `BROWSER` | `BrowserService` | `INTERNET` | ✅ **真拦**：同上 |
| `CONTACTS` | `ContactsService` | `READ_CONTACTS` | ✅ **真拦**：2026-11-10 新增 |
| `SMS` | `SmsService` | `READ_SMS`、`READ_CALL_LOG` | ✅ **真拦**：2026-11-10 新增 |
| `MEDIA` | `MediaService` | `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` | ✅ **真拦**：2026-11-10 新增 |
| `LOCATION` | `LocationService` | `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | ✅ **真拦**：2026-11-10 新增 |
| `NOTIFICATIONS` | ⚠️ 无门，但服务存在：`DeviceContextService.notify()` | `POST_NOTIFICATIONS` | ⚠️ **拦不住**：声明只进授权面，服务照样能用 |
| `CLIPBOARD` | ⚠️ 无门，但服务存在：`DeviceContextService.clipboard()` | 无 | ⚠️ **拦不住**：同上 |
| `INSTALL_PACKAGES` | ⚠️ 无对应服务（宿主只装**插件**，不装 APK） | `REQUEST_INSTALL_PACKAGES`（已声明，未使用） | ⚠️ 声明即审计 |
| `NETWORK` | ⚠️ 无服务可借 | `INTERNET` | ⚠️ 声明即审计 |
| `ROOT_SHELL` | ⚠️ 无服务可借（`su` 由 OS 层鉴权） | 无 | ⚠️ 声明即审计 |

**`NOTIFICATIONS` / `CLIPBOARD` 这一行的矛盾要记清楚**：服务真实存在且不设门。
文档若照抄 `HostPermission` 的 KDoc 会得出错误结论——KDoc 需要修（见 §7 待办）。

---

## 6. 宿主现在**没有**的东西

这一节比上面所有节都重要，因为它决定了哪些插件现在根本写不了。

| 缺的能力 | 缺的是什么 | 补的前提 | 对插件的影响 |
|---|---|---|---|
| 时间维度 | 无调度服务（`BackgroundService` 明确"不带调度语义"） | 宿主加跨重启的定时/唤醒服务 | 无法"半小时后提醒我" |
| 写通讯录 | 无 `WRITE_CONTACTS` 声明，`ContactsService` 只读 | 加权限 + 加写方法 | 无法新建/修改联系人 |
| 收发短信 | 无 `SEND_SMS` / `WRITE_SMS` 声明，`SmsService` 只读 | 同上 | 无法发短信，只读不收 |
| 媒体删除 | `MediaService` 只读 | Android 11+ 要走 `createDeleteRequest` 并二次确认，是独立交互流程 | 无法删照片 |

**2026-11-10 已补进服务**：通讯录、短信、媒体库、定位（见 §3.2 与 §5）。
它们的运行时权限早就在 manifest 里，引导页的「个人数据」「相册」两组授权也已经存在——
差的只是服务这一层，这次补上了。

**只读是刻意的边界**，不是没做完：写通讯录、发短信、删照片都需要额外权限，
而 `ContactsService` / `SmsService` / `MediaService` 一开始就只做读。
接口里没有写方法，比有一个必然抛 `SecurityException` 的方法更清楚。

`host-app/AndroidManifest.xml` 已经把上面这些的 Android 权限**全部声明了**
（`READ_CONTACTS`、`READ_SMS`、`READ_CALL_LOG`、`READ_MEDIA_*`、`ACCESS_FINE_LOCATION`、
`SCHEDULE_EXACT_ALARM`、`RECORD_AUDIO`、`BODY_SENSORS`、`QUERY_ALL_PACKAGES`……）。

**所以缺的不是权限，是服务。** 见面清单里那 5 条 ❌ 全部卡在这里。

---

## 7. 维护规则

1. **新增服务**：先在 `host-api` 声明 `ServiceKey`，再在本文件对应分组加一行，最后写实现。
2. **新增权限**：`HostPermission` 加常量 → 在 §5 加一行并标明执行力档位 → 更新 `catalog.json`。
3. **"保留词"变真**：从 §5 的 ⚠️ 档移到 ✅ 档，并在提交信息里写明。
4. **`HostPermission` 的 KDoc 与本文档冲突时，以本文档为准**，并顺手修 KDoc。

### 已知债务

- [ ] `HostPermission` 的 KDoc 说 `NOTIFICATIONS` / `CLIPBOARD` / `INSTALL_PACKAGES`
      "没有服务"，前两个已过期（§5）。
- [ ] `agent/approval/policy` 由 `agent-shell` 发布，归属可疑（§4）。
- [ ] `ev-manifest-spec.md` 声明的 `hostApi` 语义化范围此前**无代码读取**，
      §8 记录本次修复。

## 8. 版本门（本次一并落地）

实验期：`PLUGIN_API_VERSION = 1`，`PluginValidator.apiGate` 做整数等值判断。
问题：manifest 规范里写了 `hostApi: ">=1.0.0 <2.0.0"` 这种范围，但**没有任何代码读它**——
规范与实现漂移。

正式版规则见 `docs/sdk/versioning.md`。
