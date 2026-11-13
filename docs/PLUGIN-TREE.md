> ⚠️ **2026-11-10 起：本文描述的 17 个插件已全部归档。**
> 源码整体移到 `/workspace/EV-archive/2026-11-10-experimental-plugins/`，
> 不再参与构建。正式版从零重写，模块名统一 `ev-` 前缀，当前只有 `ev-tools`。
> 本文保留作为设计参考；其中的插件划分、职责边界仍然可读，但**不再是当前实现**。

# 目标插件树（Spec）

状态：草案，等待确认。确认后这份文档是所有新增/改造插件的验收依据。

本文回答三件事：**要有哪些插件、每个插件能干什么、前端开放什么**。
凡是与本文冲突的实现，以本文为准；需要改本文时先改文档再改代码。

---

## 0. 分层

```
内核（冻结，不可改）
  loader / core / hmr / include / packages
        ↑
宿主 host：启动、装载、权限门、服务目录、安全模式、崩溃审计、插件管理
        ↑
契约层
  plugin-api   ← 宿主与插件之间的稳定面（服务 key、Manifest、权限、存储）
  agent-api    ← agent 领域契约（ModelService、ToolRegistry、AgentService…）
        ↑
能力插件（capability）     界面插件（surface）      环境插件（environment）
  agent-brain               ev-ui                    linux
  agent-model               theme-default
  agent-tools               settings
  agent-shell
  agent-device
  agent-browse / agent-web
  agent-log / agent-memory
  forge / forge-compiler
```

规则：
- **内核不动**。所有新能力都必须是插件。
- **契约只加不改**：加字段带默认值；不加枚举值（会让已编译的 `when` 崩溃）。
- 插件之间**只能通过服务目录通信**，不允许直接依赖对方的实现类。
- 每个插件必须声明 `PluginManifest`（`ManifestProvider`）；没有声明的插件在管理页显示为「未声明」。

---

## 1. 插件清单（目标）

图例：**服务** = 发布到服务目录的 key；**工具** = 注册到 `ToolRegistry` 的名字；**页面** = 前端可见入口。

### 1.1 契约与核心

| id | 职责 | 服务 | 工具 | 页面 |
|---|---|---|---|---|
| `ev.buan.plugins.agent.api` | 领域契约的定义容器，不含逻辑 | — | — | 无 |
| `ev.buan.plugins.agent.brain` | agent 主循环：预算、工具执行、审批、收尾、进度事件 | `agent/service` | — | 无（由 ev-ui 展示） |

### 1.2 模型

| id | 职责 | 服务 | 工具 | 页面 |
|---|---|---|---|---|
| `ev.buan.plugins.agent.model` | provider / Key / 模型清单；`complete()` | `agent/model`、`agent/model-providers` | — | 设置 → 模型与能力 → 模型服务 |

**配置项（前端可改）**：默认模型、温度、单次最大 token、超时。
**边界**：不做重试策略编排（属于 brain）；不做流式 UI（属于 ev-ui）。

### 1.3 工具（agent 的手）

| id | 职责 | 工具 | 页面 |
|---|---|---|---|
| `ev.buan.plugins.agent.tools` | 文件读写 | `file_read` `file_list` `file_write` `file_delete` | 设置 → 工具（只读清单） |
| `ev.buan.plugins.agent.shell` | root shell | `run_shell` | 设置 → 工具 + 独立终端页 |
| `ev.buan.plugins.agent.device` | 设备：文件、应用、剪贴板、通知，以及开关/亮度/音量/勿扰/手电筒/媒体键 | 文件 7 + 应用 3 + 剪贴板/通知 5 + 设备控制 13（共 28 个） | 设置 → 工具 |

**边界**：`run_shell` 永远 `dangerous`，永远走审批；设备工具中会改变可见状态的（点击/输入/启动）也必须 dangerous。

### 1.4 检索与记忆

| id | 职责 | 服务 | 工具 | 页面 |
|---|---|---|---|---|
| `ev.buan.plugins.agent.browse` | 网页抓取与搜索 | — | `web_fetch` `web_search` | 内置浏览器页 |
| `ev.buan.plugins.agent.memory` | 按会话的事实记忆 | `agent/memory` | — | 设置 → 记忆 |
| `ev.buan.plugins.agent.log` | 事件审计（截断/隔离/缺口恢复） | — | — | 设置 → 诊断 → 事件日志 |

**待定**：`agent-web`（本机网页控制台）与 `agent-browse` 职责有重叠，见 §5。

### 1.5 开发工具

| id | 职责 | 工具 | 页面 |
|---|---|---|---|
| `ev.buan.plugins.forge` | 设备内编译并安装插件 | `forge` `forge_build` `forge_install` | 设置 → 高级 → 插件工坊 |
| `ev.buan.plugins.forge.compiler` | 编译链（源码 → dex） | 服务 `forge/compiler` | 无 |

### 1.6 环境

| id | 职责 | 工具 | 页面 |
|---|---|---|---|
| `ev.buan.plugins.agent.phone` | 个人数据：日历读写（宿主契约）+ 闹钟/便签（root provider） | 需要日历时；闹钟/便签需要 root |
| `ev.buan.plugins.agent.phone` | 个人数据：日历读写（宿主契约）+ 闹钟/便签（root provider） | `list_calendars` `search_calendar_events` `read_calendar_event` `create_calendar_event(s)` `update_calendar_event` `delete_calendar_event` `list_alarms` `set_alarm` `update_alarm_time` `set_alarm_enabled` `delete_alarm` `search_notes` `read_note` | 需要日历时；闹钟/便签要 root |
| `ev.buan.plugins.linux` | Debian 根文件系统：安装、状态、执行 | `linux_status` `linux_setup` `linux_run` | 设置 → 环境 → Linux 环境 |

**已实现**：安装（tar.gz）、`/proc /dev /sys` 自动挂载、chroot 执行、发行版探测。
**待做**：xz 解压（当前依赖系统，Android 没有）、无外网时的下载通道、apt 源预配置。

### 1.7 界面

| id | 职责 | 页面 |
|---|---|---|
| `ev.buan.plugins.ev.ui` | 主界面：对话、抽屉、快捷菜单、设置、终端、浏览器、各详情页 | 全部 |
| `ev.buan.plugins.theme.default` | 全局主题（配色/字号/间距/圆角/动效/图标/层高） | 设置 → 外观 → 主题 |

### 1.8 规划中

| id | 职责 | 说明 |
|---|---|---|
| `ev.buan.plugins.store` | 插件商店：远程清单、按需下载、安装 | 支撑「想下就下」 |
| `ev.buan.plugins.workspace` | 项目/工作区：把一组文件当作文档根 | 与 linux 配合 |

---

## 2. 前端开放什么

### 2.1 三类入口

| 入口 | 谁决定 | 内容 |
|---|---|---|
| **设置行** | 宿主 + 插件声明 | 一行标题+说明+图标，点击进入详情页或独立页面 |
| **独立页面** | 插件实现 `AgentPage`/`EvPage` | 终端、浏览器、Linux、记忆、角色、Skills |
| **工具条/抽屉** | ev-ui | 主界面的常驻入口（新建、抽屉、快捷菜单） |

### 2.2 插件详情页（由 manifest 驱动，宿主/ev-ui 渲染）

固定顺序，字段为空则整块隐藏：

1. **它做什么** —— `description`
2. **管理** —— 启用开关、存储占用与清除（来自 `PluginManagerService`）
3. **权限** —— `permissions`，逐项开关
4. **能力** —— `capabilities`（kind + 标题 + 说明）
5. **配置项** —— `settings`，按类型渲染：布尔=开关、文本=弹窗、单选=选项、动作=按钮

**一致性检查（待做）**：详情页要标注「声明了但没注册」的能力——宿主在运行时对比
`capabilities(kind=TOOL)` 与 `ToolRegistry.specs()`，不一致就红字提示。声明写错比没有声明更糟。

### 2.3 独立页面规范

- 顶部：返回 + 标题（48dp 行高，`statusBars` 内边距）
- 主体：可滚动，左右 16dp
- 底部：如有输入区，跟随键盘（`imePadding`）
- 空状态必须说清「为什么空」和「下一步做什么」

---

## 3. 命名与数据规则

- 插件 id：`ev.buan.plugins.<域>[.<子域>]`
- 服务 key：`<域>/<能力>`，如 `agent/model`、`host/theme-catalog`
- 工具名：小写下划线，全局唯一（`ToolRegistry` 会因重名抛错）
- 每个插件只写自己的存储（`PluginStorage`），不碰别的插件目录
- 卸载即清空存储；重装不还原（当前实现，需在文档确认）

---

## 4. 依赖与启动顺序

用 sidecar 的 `dependencies` 表达，宿主按拓扑激活：

```
agent-api ← agent-tools ← agent-shell
                    ↑
agent-model ← agent-brain → 需要 memory/approval（可缺省降级）

ev-ui → 需要 agent/api（工具与模型状态展示），theme-default 可选
linux → 需要 agent-tools（工具总线）、ROOT_SHELL + PLUGIN_STORAGE
```

**降级原则**：可选依赖缺失时插件仍应激活，只是少一块能力；必需依赖缺失才失败并说明。

---

## 5. 需要拍板的决策

| # | 决策 | 选项 |
|---|---|---|
| D1 | `agent-web`（网页控制台）保留还是并入 `agent-browse` | A 保留独立；B 合并 |
| D2 | Linux 环境是否默认安装 | A 默认装（开箱可用）；B 按需下载（包体小） |
| D3 | 卸载插件是否保留数据 | A 清空；B 保留 7 天 |
| D4 | 插件商店的清单来源 | A 官方仓库 URL；B 用户自填 |
| D5 | 设置页是否允许插件「自建页面」入设置树 | A 允许（能力更强）；B 只允许声明式配置项 |

---

## 6. 验收标准（每个插件）

1. `PluginManifest` 声明与实际注册**逐条一致**（工具名、服务 key）
2. 设置行能打开，详情页五块内容正确
3. 配置项改动后**真的生效**（不是只存不用）
4. 禁用后能力消失、页面给出说明；重新启用恢复
5. 卸载清空存储，重装是干净状态
6. 单元测试覆盖参数校验与错误路径；编译 + 装机通过
