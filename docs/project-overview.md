# EV 项目总览（新对话交接档案）

> 这份文档写给"第一次接触本项目的人/AI"。读完它，你应该能在不看代码的情况下
> 说清楚：EV 是什么、仓库怎么切、哪些规矩不能破、下一步干什么。
> 最后更新：2026-10-25。

## 1. 一句话

EV 是一个跑在 **root 安卓机**上的**插件化宿主 app**：插件是 jar/APK，扔进
`/storage/emulated/0/EV/mods` 就能装；当前的主线产品是**一个能动手操作手机的 agent**
（不只是聊天）。技术底座是 Cordis 插件运行时的 Kotlin 移植（本仓库同仓维护，已冻结）。

## 2. 仓库地图

```
core/ loader/ hmr/ include/ packages/ packager/   ← 内核：插件运行时，已冻结，只当依赖用
utils/ timer/ logger-console/                     ← 内核辅助模块（同属冻结阵营）
host/                                             ← 宿主 app（Android 进程：扫描/校验/加载/UI 外壳）
host-core/                                        ← 宿主纯 JVM 逻辑（校验器、权限元数据、依赖解析）
plugin-api/                                       ← 宿主↔插件契约（服务接口、权限、PLUGIN_API_VERSION=1）
ui-kit/                                           ← 设计系统（ev.buan.ui.*，宿主与插件共用）
plugins/settings                                  ← 设置插件
plugins/eta-ui                                    ← 主界面插件（唯一界面插件，默认主页，~7700 行）
plugins/agent-api                                 ← agent 契约（AgentService/ModelService/ToolRegistry/MemoryService）
plugins/agent-model                               ← 模型能力（多 Provider，OpenAI 兼容）
plugins/agent-tools                               ← 工具能力（插件存储区文件读写）
plugins/agent-memory                              ← 记忆能力（JSON 会话记忆）
plugins/agent-brain                               ← 编排（工具轮、能力缺失降级）
plugins/hello|probe|coldapi|ping-*                ← 实验插件，已摘除构建（代码保留，勿在主构建引用）
demo/                                             ← 独立 Gradle 构建，已冻结，勿动
docs/                                             ← 文档，导航见 docs/README.md
scripts/kernel-freeze.sh                          ← 内核冻结门禁
scripts/release-smoke.sh                          ← release 冒烟 + 真机验收清单
```

## 3. 四条铁律

1. **内核冻结**（[kernel-freeze.md](kernel-freeze.md)）：`loader core hmr include packages`
   只当依赖用，改动会被门禁拒。例外要走评审 + 白名单登记。
2. **插件切分按"权限 + 能否单独关闭"**，不按代码结构切。最小单位是"能力"。
3. **插件直接实现 `org.cordis.Plugin`**，不加适配层（[ADR-0002](adr/0002-plugin-interface.md)）。
4. **权限 = 声明 + 用户授权 + API 门控 + 审计**，不承诺 OS 级沙箱
   （[ADR-0003](adr/0003-permission-scope.md)）。插件同进程同 UID 运行。

## 4. 当前状态（2026-10-25）

- 架构加固（T01–T31）已完成并真机验收（PMX110 / Android 17），
  记录在 [archive/2026-10-24-arch-hardening/](archive/2026-10-24-arch-hardening/)。
- 主构建收敛为 9 个插件：settings、eta-ui、agent-api/model/tools/memory/brain/web/shell。
  旧聊天插件 `frontend` 已删除，eta-ui 是唯一界面。
- 内核冻结门禁为绿（基线 `7b13e6b`）。
- agent 链路已通：eta-ui 聊天 → brain 编排 → model 收发 + tools 工具轮（≤3 轮）+ memory 多轮。
- 审批链路已通：`ToolSpec.dangerous` + `ApprovalService`（eta-ui 发布审批门，弹窗三选：
  拒绝/允许一次/本次运行放行）；高危调用无门即拒（fail-closed），拒绝理由回填给模型。
- Web 控制台已通：agent-web 在局域网 8765 端口服务 React 控制台（令牌鉴权），
  聊天走 `/api/chat`；`/api/approve` 待接审批总线（当前 501）。
  能力缺失时降级为占位回复，不崩宿主。

## 5. 产品方向：能动手的 agent

差异化不在聊天（打不过官方 app），在 **root + 插件化**。路线图：

1. **agent-shell 插件**（已落地）：`run_shell` 工具经 su 执行 root 命令，标记 `dangerous`，
   每次调用由 brain 经 `ApprovalService` 审批门挂起等人工决定（无门 fail-closed）。
   `ROOT_SHELL` 权限走现有权限框架（声明/授权/审计——宿主无法门控 su，见枚举 KDoc），
   root 不是默认能力。
2. **设备上下文插件**：已装应用、通知、剪贴板、电量/存储——agent 的"眼睛"。
3. **定时任务**：内核 `timer` + 宿主 `BackgroundService`，"每天早 8 点查天气发通知"。

并行工程线：**扩展点机制**（[extension-points.md](extension-points.md)，草案）——
插件经 sidecar 声明 + ServiceDirectory 投递，把 UI 挂进其他插件声明的插槽，
v1 只开 `ev.settings.page` 一个插槽，解决"能力插件没有自己页面"的问题。

## 6. 协作提示（给下一段对话的 AI）

- 先读 [docs/README.md](README.md) 拿文档导航，再读 [architecture.md](architecture.md) 拿事实；
  计划类内容看 archive/ 时注意那是历史快照。
- 提方案前先想清楚落在哪一层：内核（默认不许）/ 宿主 / 契约 / 插件。
- 新功能优先做成新插件；改宿主是次选；动内核要有充分理由。
- 构建/验收入口：`scripts/release-smoke.sh`；内核完整性：`scripts/kernel-freeze.sh`。
- 已知技术债见根 README「已知缺口」节（插件 UI 无错误边界等；`tool_calls` 原生协议已于
  2026-10-25 落地），按需偿还，不提前还。
