# AGENTS.md — 在 EV 仓库里干活前先读这个

本文件是给 AI 编码代理的工作指南。项目是什么、文档在哪、怎么构建、怎么部署到手机、
以及**踩过的坑**。设计意图读 [docs/](docs/README.md)，这里只写"怎么做"和"别做什么"。

---

## 1. 这是什么

Android 宿主 + 插件平台。内核（`core` / `loader` / `hmr` / `include` / `packages`，派生自
cordis-kotlin）**已冻结**，只当依赖用；业务都在 `:host`、`:ui-kit`、`:plugin-api` 和 `:plugins:*`。

| 模块 | 职责 |
|---|---|
| `:host` | Android 应用（EV）：启动、插件管理、安全模式、崩溃审计 |
| `:ui-kit` | 设计系统：`EvThemeTokens`、`EvDrawer`、通用组件 |
| `:plugin-api` | 插件契约（`AgentService`、`AgentReply`、`AgentEvent`、`AgentProgress`…） |
| `:plugins:*` | 9 个插件，见 `settings.gradle.kts` |
| `:plugins:agent-brain` | agent 主循环：预算、工具执行、收尾、进度事件 |
| `:plugins:ev-ui` | 聊天界面（最重要的插件） |
| `:plugins:agent-tools` / `agent-shell` | 文件工具 / shell（审批门控） |

入口文档：`docs/project-overview.md` → `docs/README.md`（分类索引）。
UI 规范：`docs/DESIGN-EXTRACT.md`（从 Eta 提取，**判定栏写"照搬"的必须照抄数值**）。

---

## 2. 构建（这台机器很特殊，别用标准命令）

主机是 aarch64。Kotlin 编译、Gradle、R8 全是 JVM 任务，原生跑；**唯一需要转译的是 aapt2**——
官方只发 x86-64 二进制，这里用 `qemu-x86_64-static` 包了一层，通过
`/root/.gradle/gradle.properties` 的 `android.aapt2FromMavenOverride` 覆盖 AGP 自带的那份。
所以"慢"主要慢在资源编译，不是整条链。**必须**：

```bash
cd /root/EV
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
ANDROID_HOME=/opt/android-sdk \
/opt/gradle-9.7.1/bin/gradle <tasks> --offline --console=plain \
  -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk-arm64
```

- **`--offline` 是硬要求**：官方源在这里走不通
- JDK 21（Eta 那个项目要 JDK 25，别混用）
- 常用任务：`:host:assembleDebug`、`:plugins:<id>:bundlePlugin`、`:plugins:<id>:testDebugUnitTest`

---

## 3. 改完必须做的事

1. **跑测试**：`:plugins:<id>:testDebugUnitTest`。只说"编译通过"不算完成。
2. **打包**：改动的插件跑 `:plugins:<id>:bundlePlugin`，产物在 `plugins/<id>/build/dist/<id>.jar`。
3. **验产物**：`unzip -p ... | strings classes*.dex | grep <新符号>`。
   ⚠️ **只对 md5 是没用的**——md5 只能证明"传过去的是本地那份"，证明不了"本地那份是对的"。
   曾经因为漏打 `agent-api` 的 bundlePlugin，推上去的 jar 里没有新增的 `sendStreaming`，
   线上表现为 `NoSuchMethodError`，而 md5 完全一致。
4. **推设备**：`/app/export`（HTTP 桥）导出到 `Download/DSHA/`，再 `cp` 到 `/sdcard/EV/mods/`。
5. **重启 EV**：插件在启动时复制到 `files/mods/` 再加载，**改插件必须重启进程**。
6. **动了公共 API 就跑快照**：改 `:plugin-api` 或 `:ui-kit` 的公开声明后，
   `gradle :plugin-api:apiSnapshot :ui-kit:apiSnapshot` 并提交 `api/*.txt`。
   本地单测不会管这个，`apiSnapshotCheck` 只在 CI 跑——忘了就是一条红的 main。

---

## 4. 铁律

- **不要改内核目录**（`loader/` `core/` `hmr/` `include/` `packages/`）。`scripts/kernel-freeze.sh` 会拦。
- **契约只加不改**：给 `plugin-api` 的接口加方法/字段可以（带默认值），
  改签名、删字段、加枚举值（会让已编译的 `when` 崩溃）都不行。
  真正不兼容时应当 bump `apiVersion`——这是已知缺口，目前还没做。
- 插件类**不得**位于 `org.cordis.*`。
- 共享包清单唯一来源 `gradle/shared-packages.txt`，改它要同步三处（见 README）。
- **不要自己发明设计规范**：UI 数值查 `docs/DESIGN-EXTRACT.md`。

---

## 5. UI 约定（最近踩过的）

- 字号层级：正文 `body` 16sp / 标签 `label` 14sp / 注释 `caption` 13sp / 极小 `micro` 11sp。
  Eta 的对应关系是 body1 16 / body2 14 / footnote1 13 / footnote2 11。
  **不要把信息行写成 `caption`**，读起来像脚注；工具折叠块用 14sp + 主文字色。
- 状态色：运行中用 `textPrimary`，完成后降到 `textSecondary`，失败用 `danger`。
- 动画：弹簧优先；`EvThemeTokens.reducedMotion` 必须尊重。
- 参考实现别照抄结构：Eta 是 Jetpack Compose + 自研 Markdown，EV 是 miuix 0.9.4。

---

## 6. 部署与调试环境

- 设备：Android 17，1264×2780。宿主机 `/app/*` HTTP 桥在 `127.0.0.1:3090`
  （`curl -H @/root/.dsh/.bridge_headers http://127.0.0.1:3090/app/help`）。
- `adb-shell` 有守卫：`am start` / `pm install` / `screencap` / `uiautomator` 会被拦，
  `am force-stop`、`cp`、`md5sum`、`strings` 之类可用；复杂管道会被拒，命令要单条。
- APK 构建产物：`host/build/outputs/apk/debug/host-debug.apk`。

---

## 7. 汇报口径

- 说清了才叫做完：**做了什么 / 还剩什么 / 卡在哪**，附文件行号与测试原始输出。
- 不确定就说"未实测"，不要用推理冒充结论。例：某些行为只能在真机上验证，
  那就明确写"未实测"，并说清楚为什么。
- 出错先修根因；发现自己的判断错了要明说（例如"明细其实一直在落库，是我自己置空的"）。

---

## 8. 已知遗留（别以为是 bug）

- `ChatMessage.traces` 现在只写不读，是死数据，待清理。
- `apiVersion` 未随契约变更 bump，跨版本插件混装会报 `NoSuchMethodError` 而不是清晰错误。
- 设备上 EV 进程**拿不到 root**（`su` 不存在），`run_shell` 因此不可用；
  宿主的 shell 能力要等接 Shizuku 或授权才有效。
