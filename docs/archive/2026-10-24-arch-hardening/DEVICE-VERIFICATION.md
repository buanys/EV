# 真机验收记录（2026-10-24）

> 2026-10-24 后续：Eta UI 已拆为独立 `eta-ui` 插件（`ev.buan.plugins.eta.ui`）。本记录中的 UI 截图来自拆前
> `frontend` 内置版本；拆后需要把 `eta-ui.jar` 与 `frontend.jar` 一起重新投放并复验（设备在拆分后未连接）。
> P2-A 后 eta-ui 声明 `PLUGIN_STORAGE`：真机还需在管理页授予「插件存储」并验证 `sessions.json` 重启恢复；
> 未授权时应回退 frontend。P3 后还需验证抽屉 → 设置页、外观即时重绘与 `EvPage` 返回箭头点击。
> P4-0 后还需验证工具面板（只读 specs/折叠/刷新）、浏览器面板（加载/后退/前进/刷新/系统返回）
> 与记忆面板（读当前会话 agent-memory、两步清空、聊天历史不被删），以及角色面板（新建/选中/
> 发送带 systemPrompt/删除在用角色后会话变无角色）与 Skills 面板（新建/选中、角色+Skill 组合
> 指令、删除在用 Skill 后会话变无 Skill）。模型提供商页还需验证：配置 DeepSeek→保存→测试连接→
> 拉取 /models→设为当前→重启仍在；停用 agent-model 时有可读提示。这一轮设备验收尚未执行。

本文件记录 T01–T31 完成后在真实 Android 设备上的手工验收。它补充 README 的
「真机验收清单（agent 层）」与 `scripts/release-smoke.sh` 中的清单：自动化门禁只覆盖构建与
纯 JVM 单测，涉及权限 UI、插件整页、前台生命周期与真实模型的路径必须人工执行。

## 1. 环境

| 项 | 值 |
|---|---|
| 设备 | PMX110（ColorOS，KernelSU root） |
| Android | 17 / API 37 / arm64-v8a |
| 屏幕 | 1264 × 2780 |
| 构建 | debug APK 57,039,038 B；release APK 28,954,772 B（`-PallowDebugSigning=true`，debug 签名） |
| 插件安装 | 9 个 jar + json 手工放入 `EV/mods`（settings / probe / frontend / coldapi / agent-api / agent-model / agent-tools / agent-memory / agent-brain） |
| 权限 | `su -c "appops set ev.buan MANAGE_EXTERNAL_STORAGE allow"`；`POST_NOTIFICATIONS` 已授予 |

设备上另保留了历史负向夹具（`api2`、`dup`、`fake-jvm`、`plugin.fake.jar` 等），用于验证
`API_VERSION_MISMATCH` / `DUPLICATE_PLATFORM_CLASS` / `SIGNATURE_HOST_MISMATCH` / 入口类缺失；
它们不属于本轮交付，README 的 9 jar 清单不包含它们。

## 2. 结论

Agent 层、权限/存储、插件整页、安全模式、release 包均已真机跑通：

| # | 验证项 | 结果 |
|---|---|---|
| 1 | debug APK 安装、首页聊天（frontend）渲染 | ✅ |
| 2 | 管理页列出插件、权限、每插件存储占用/清理 | ✅ |
| 3 | 未授权 → `PERMISSION_DENIED`，授予后自动激活；撤销后再次拒载 | ✅ |
| 4 | probe 15 项全绿（API 版本、19 个共享包、外观、权限、存储读写/原子写/清理、Compose） | ✅ |
| 5 | coldapi 8 项全绿（OkHttp / Jsoup / coroutines 共享运行时） | ✅ |
| 6 | ping-api / ping-impl / ping-ui 跨插件契约与设置页、hello jar 的 Compose 渲染 | ✅ |
| 7 | `PluginPageActivity` 打开插件整页（T25 之前会永久弹回主页） | ✅ |
| 8 | 未配置模型时的降级文案（brain 存在、model 未配置） | ✅ |
| 9 | 真实模型问答（DeepSeek 兼容服务，`deepseek-flash`） | ✅ |
| 10 | 工具轮：写 `notes.txt`，回复基于工具结果 | ✅ |
| 11 | 记忆多轮：追问上一轮写入内容，回答正确 | ✅ |
| 12 | 管理页存储占用变化：tools 5 B、memory 738 B、model 113 B | ✅ |
| 13 | 安全模式：崩溃标记 → 重启不加载插件 → 退出后恢复，无需杀进程 | ✅ |
| 14 | release APK 覆盖安装后权限保留，probe/coldapi 仍绿 | ✅ |

### 2.1 证据截图

| 场景 | 文件 |
|---|---|
| Agent 工具轮 + 记忆多轮 | [evidence/device-agent-tools-memory.png](evidence/device-agent-tools-memory.png) |
| 管理页：memory 738 B、model 113 B | [evidence/device-storage-memory-model.png](evidence/device-storage-memory-model.png) |
| 管理页：tools 5 B | [evidence/device-storage-tools.png](evidence/device-storage-tools.png) |
| 安全模式首屏 | [evidence/device-safe-mode.png](evidence/device-safe-mode.png) |
| 插件整页（probe / ABI 探针） | [evidence/device-plugin-page-probe.png](evidence/device-plugin-page-probe.png) |
| coldapi + ping 插件 | [evidence/device-coldapi-ping.png](evidence/device-coldapi-ping.png) |
| 管理页权限与存储卡片 | [evidence/device-manager-permissions.png](evidence/device-manager-permissions.png) |

## 3. 关键操作与证据

### 3.1 安装与插件目录

```powershell
adb install -r host/build/outputs/apk/debug/host-debug.apk
adb shell mkdir -p /sdcard/EV/mods
# 9 个 jar/json 逐个 adb push 到 /sdcard/EV/mods
adb shell su -c "appops set ev.buan MANAGE_EXTERNAL_STORAGE allow"
```

宿主 `sync` 把外部 jar 复制到 `files/mods/<id>.apk`，校验 sidecar 后按依赖拓扑激活。
`SIGNATURE_HOST_MISMATCH` 等负向夹具的错误只出现在「同步问题」区，不影响正常插件。

### 3.2 权限门控

管理页对 `agent.model` 显示「网络访问」「插件存储」两项，未授权时卡片显示：

```text
PERMISSION_DENIED: 插件 ev.buan.plugins.agent.model 缺少权限 NETWORK, PLUGIN_STORAGE
（在插件管理页授予后自动启用）
```

点击权限行授予后自动重新激活；再点一次撤销，立即回到 `PERMISSION_DENIED`。
probe / agent-tools / agent-memory 的 `PLUGIN_STORAGE` 同样是真门控。

注意：`NETWORK` 只是声明 + 用户授权 + 审计，不是网络门控（宿主 INTERNET 由
`okhttp-android` 传递引入，插件同 UID 直接调用共享 OkHttp）。要验证「撤权导致拒载」，
应撤销 `PLUGIN_STORAGE` 或 `BACKGROUND`，而不是 `NETWORK`。

### 3.3 Agent 端到端

配置：`agent-model` 填 `https://api.deepseek.com/v1`、API Key、模型 `deepseek-flash`，保存后
卡片显示「已配置: deepseek-flash」。

真机对话（截图见 2.1）：

```text
用户：你好
助手：你好！很高兴见到你 😊 有什么我可以帮你的吗？……

用户：把 hello 写入 notes.txt
助手：已经写好了 ✅ 文件 notes.txt 已创建，内容为 hello（共 5 字节）。

用户：刚才写了什么
助手：刚才写入 notes.txt 的内容就是两个字符：hello ……
```

落盘证据（`adb shell su -c "find /data/data/ev.buan/files/plugins -type f"`）：

```text
.../ev.buan.plugins.agent.tools/notes.txt                          # 内容 hello
.../ev.buan.plugins.agent.memory/memory/sessions/d51a…694.json     # 多轮会话
.../ev.buan.plugins.agent.model/config/model.json                  # 模型配置
.../ev.buan.plugins.probe/probe/last-run.txt
```

管理页逻辑占用：`agent-tools` 5 B / 20 MB、`agent-memory` 738 B / 20 MB、
`agent-model` 113 B / 20 MB；目录 `du` 偏大只是文件系统块分配，不是统计错误。

### 3.4 安全模式

模拟一次「上次运行异常退出」：向 `files/crash/crash.pending` 写入标记（真实崩溃由
`EvApplication` 的未捕获异常处理器在崩溃时写同一标记）。

```powershell
adb shell su -c "cp /data/local/tmp/crash.pending /data/data/ev.buan/files/crash/crash.pending"
adb shell am force-stop ev.buan
adb shell am start -n ev.buan/.MainActivity
```

结果：首页显示「安全模式」兜底页；管理页顶部出现安全模式横幅与「退出安全模式」按钮，
所有插件卡片标注「安全模式：上次运行崩溃，本次启动未加载」。
点击退出后 `crash.pending` 被删除、`last.txt` 保留，返回首页直接出现聊天界面，无需杀进程
（T25 的 `HostRuntime.state` 刷新）。

### 3.5 Release 包

```powershell
.\gradlew.bat :host:assembleRelease -PallowDebugSigning=true
adb install -r host/build/outputs/apk/release/host-release.apk
```

覆盖安装后权限与插件数据保留；管理页、probe 15 项、coldapi 8 项、插件整页均正常，
说明 R8 后共享包清单与 `-dontobfuscate` 规则没有破坏插件 ABI。

## 4. 仍未验证

- **真实崩溃 handler**：安全模式用 `crash.pending` 标记模拟，未让插件真实抛异常触发
  `Thread.setDefaultUncaughtExceptionHandler`（该路径有 host-core 单测）。
- **BACKGROUND 前台服务**：当前没有插件声明 `BACKGROUND`，`BackgroundService.acquire`
  没有活体调用方；通知渠道、`specialUse` 类型、引用计数与回收未真机观察。
- **通知权限被拒时的前台服务表现**：`POST_NOTIFICATIONS` 已授予，未测试拒绝路径。
- **多窗口/分屏下的插件整页恢复**：未覆盖。

## 5. 复现所需材料

- 设备已授予「所有文件访问权限」；
- `EV/mods` 放 9 个 jar/json（agent 的 5 个 + frontend + settings + probe + coldapi）；
- 一个可用的 OpenAI 兼容服务（本轮用 DeepSeek `deepseek-flash`）；
- 管理页依次授予：`agent-model` 的 NETWORK + PLUGIN_STORAGE、`agent-tools` / `agent-memory`
  / `probe` 的 PLUGIN_STORAGE。
