# 在这台手机上编译 EV

> 这份文档只写**怎么做**：环境在哪、命令是什么、产物怎么验、卡住了怎么办。
> 为什么这么设计看 [ADR-0004](adr/0004-on-device-forge.md)；
> 设备端自己编插件那条链路看 [research/on-device-compiler.md](research/on-device-compiler.md)。
>
> 最后更新：2026-10-31（本轮全流程实跑：宿主 APK、12 个插件、测试、快照、推设备、真机验证）。

---

## 1. 这台机器的特殊之处

主机是 **aarch64 Android**，但构建几乎全是 JVM 任务：

| 环节 | 怎么跑 | 说明 |
|---|---|---|
| Kotlin 编译 / Gradle / R8 | **原生 aarch64** | JVM 任务，不需要转译 |
| **aapt2** | **qemu 转译 x86-64** | 官方只发 x86-64 二进制；这是"慢"的主要来源 |
| 其余（dex、打包、签名） | 原生 | — |

转译层的实现只有两行——`/opt/tools/aapt2-9.4.1/aapt2` 是个 shell wrapper：

```sh
#!/bin/sh
exec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu \
     /opt/tools/aapt2-9.4.1/aapt2.x86_64 "$@"
```

再通过 `/root/.gradle/gradle.properties` 让 AGP 用这份而不是它自带的：

```properties
android.aapt2FromMavenOverride=/opt/tools/aapt2-9.4.1/aapt2
```

**验证转译层是否可用**（不构建也能验）：

```sh
chroot <rootfs> /usr/bin/env -i PATH=/usr/bin:/bin \
  /bin/sh -c '/opt/tools/aapt2-9.4.1/aapt2 version'
# 期望：Android Asset Packaging Tool (aapt) 2.20-15978811
```

---

## 2. 构建环境不在本 app 的沙箱里

仓库和工具链都放在**另一个应用的 Ubuntu rootfs** 里（本 app 的沙箱里没有 JDK/SDK）：

```
/data/user/0/com.dsh.client/files/linux/ubuntu        ← rootfs
/data/user/0/com.dsh.client/files/linux/ubuntu/root/EV ← 仓库（构建机的副本）
```

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `/usr/lib/jvm/java-21-openjdk-arm64` | 21.0.12 |
| Gradle | `/opt/gradle-9.7.1/bin/gradle` | 9.7.1 |
| Android SDK | `/opt/android-sdk` | platforms `android-36 / 36.1 / 37.0 / 37.2 / CANARY` |
| Gradle 缓存 | `/root/.gradle` | 约 5.1 GB，**离线构建靠它** |
| aapt2 覆盖 | `/opt/tools/aapt2-9.4.1/` | 经 qemu 转译 |

> ⚠️ 仓库有两份：本工作区（`$HOME/workspaces/EV`）与构建机（`.../root/EV`）。
> **它们不是同一个目录**，改了代码必须同步过去再构建。

---

## 3. 进容器（每台新机器/每次重启后都要做）

rootfs 里没有 `/proc`、`/dev`、`/sys` 的挂载，JVM 会直接报
`libjli.so: cannot open shared object file`——**看到这个报错，九成是没挂 `/proc`**。

```sh
U=/data/user/0/com.dsh.client/files/linux/ubuntu

# 1) 挂上三件套（已有则跳过）
for m in proc dev sys; do
  mkdir -p "$U/$m"
  mountpoint -q "$U/$m" 2>/dev/null || mount --bind /"$m" "$U/$m"
done

# 2) 自检
chroot "$U" /usr/bin/env -i PATH=/usr/bin:/bin \
  /usr/lib/jvm/java-21-openjdk-arm64/bin/java -version
```

`env -i` 不是洁癖：容器里的默认环境继承了 Android 的 `PATH`，会把宿主机的
toybox 命令混进来，Gradle 子进程偶尔就撞上不兼容的参数。

---

## 4. 构建命令（模板）

所有 Gradle 调用都是这一套前缀，后面接任务名：

```sh
U=/data/user/0/com.dsh.client/files/linux/ubuntu

chroot "$U" /usr/bin/env -i \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/root TERM=dumb LANG=C.UTF-8 \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
  ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  /bin/bash -lc "cd /root/EV && /opt/gradle-9.7.1/bin/gradle <任务> \
    --offline --console=plain --no-configuration-cache \
    -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk-arm64"
```

- **`--offline` 是硬要求**：官方源在这台机器上走不通，缺依赖就是缺，不会现下载。
- **JDK 必须显式给 21**：容器默认 `java` 是 25，和 AGP/Kotlin 的预期不一致。
- `--no-configuration-cache`：`bundlePlugin` 本身不兼容配置缓存（`gradle/plugin-verify.gradle.kts`
  已声明），混用时 Gradle 会丢弃缓存条目、噪音很多；干脆关掉。

### 四个常用目标

```sh
# 宿主 APK（约 45 MB）→ host/build/outputs/apk/debug/host-debug.apk
:host:assembleDebug

# 单个插件容器 → plugins/<id>/build/dist/<id>.jar + <id>.json
:plugins:<id>:bundlePlugin

# 单测（宿主与插件）
:plugins:<id>:testDebugUnitTest
:host:testDebugUnitTest :host-core:test :plugin-api:testDebugUnitTest

# 公开契约快照（改了 plugin-api / ui-kit 必须跑并提交）
:plugin-api:apiSnapshot
```

### 一次打全部 12 个插件

```sh
:plugins:settings:bundlePlugin :plugins:ev-ui:bundlePlugin \
:plugins:agent-api:bundlePlugin :plugins:agent-model:bundlePlugin \
:plugins:agent-tools:bundlePlugin :plugins:agent-memory:bundlePlugin \
:plugins:agent-brain:bundlePlugin :plugins:agent-log:bundlePlugin \
:plugins:agent-device:bundlePlugin :plugins:agent-browse:bundlePlugin \
:plugins:agent-web:bundlePlugin :plugins:agent-shell:bundlePlugin \
:plugins:forge:bundlePlugin :plugins:forge-compiler:bundlePlugin
```

（`forge-compiler` 需要先有载荷，见第 9 节。）

---

## 5. 离线能跑到什么程度

| 目标 | 能否离线跑 | 说明 |
|---|---|---|
| `:host:assembleDebug` | ✅ | 首次约 50 s（含 aapt2 转译），之后增量 10–15 s |
| `:plugins:*:bundlePlugin` | ✅ | 12 个插件一起约 13 s（已 up-to-date 时更快） |
| `:host-core:test` / `:host:testDebugUnitTest` / `:plugin-api:testDebugUnitTest` | ✅ | 本轮：host-core 120、host 36、plugin-api 4 例全过 |
| `:plugins:*:testDebugUnitTest` | ✅ | 各插件自带用例 |
| **根 `test`（含内核 JVM 测试）** | ❌ | `:include` 需要 `com.charleskorn.kaml:0.78.0`，缓存里没有；`--offline` 下必然失败 |

需要内核 JVM 测试时，得先有网络把 `kaml` 拉进缓存——这台机器上目前做不到。

---

## 6. 验产物：不看 md5，看符号

md5 只能证明"传过去的是本地那份"，不能证明"本地那份是对的"。
历史上就出过：漏打 `agent-api` 的 `bundlePlugin`，推上去的 jar 里没有新增的
`sendStreaming`，线上表现为 `NoSuchMethodError`，而 md5 完全一致。

```sh
J=plugins/ev-ui/build/dist/ev-ui.jar
T=$(mktemp -d); (cd "$T" && unzip -q "$J")
cat "$T"/classes*.dex | strings | grep -c 'ev.buan.plugins.ev.ui.EvUiPlugin'   # 期望 > 0
```

宿主 APK 用 aapt2 验清单与资源（顺手证明转译层产物是对的）：

```sh
chroot "$U" /usr/bin/env -i PATH=/usr/bin:/bin \
  /bin/sh -c 'cd /root/EV/host/build/outputs/apk/debug && \
    /opt/tools/aapt2-9.4.1/aapt2 dump badging host-debug.apk | head -5'
# 期望：package: name='ev.buan' … minSdk 33 / targetSdk 37
```

---

## 7. 推设备与生效（最容易卡的一段）

### 7.1 推

```sh
cp plugins/<id>/build/dist/<id>.jar  /sdcard/EV/mods/
cp plugins/<id>/build/dist/<id>.json /sdcard/EV/mods/     # ← 必须成对，同名
```

- **只有 jar 没有 json = 宿主安静跳过**，日志里没有任何线索，表现像"插件根本没装"。
- sidecar 里 `dependencies` 少写一项 = 运行时 `NoClassDefFoundError`
  （例如要用 `ToolRegistry` 就必须登记 `ev.buan.plugins.agent.api`）。

### 7.2 未签名审批（`autoTrustUnsigned = false`）

宿主按 **id + sha256** 逐份记录批准，所以**每次重新打包都要重新批准一次**：

```sh
# 宿主的审批表（等价于在插件页点"确认"）
P=/data/data/ev.buan/shared_prefs/ev.unsigned.xml
H=$(sha256sum /sdcard/EV/mods/<id>.jar | cut -d' ' -f1)
sed -i "/name=\"<id>\"/d" "$P"
sed -i "s|</map>|    <string name=\"<id>\">$H</string>\n</map>|" "$P"
```

改 prefs 前先 `am force-stop ev.buan`，否则进程内的缓存会把它盖回去。

### 7.3 生效：手推文件仍然要两次启动

第一次启动：落盘到 `files/mods/`；第二次启动：加载并激活。
（`PluginInstallService` 那条路径能在同进程内立即激活，但手工拷文件走的是启动期 sync。）

```sh
am force-stop ev.buan; am start -W -n ev.buan/ev.buan.MainActivity
# 等 ~25 s，再来一次
```

### 7.4 装新宿主 APK

```sh
am force-stop ev.buan
pm install -r -d /root/EV/host/build/outputs/apk/debug/host-debug.apk
```

`-d` 允许降级（versionCode 常年是 1）。签名一致才能覆盖；不一致会失败但不会把已装的删掉。

### 7.5 三个"环境"而不是"代码"的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `run_shell` 拿不到 root | 首次调用 KernelSU 会弹授权 | 让用户点一次允许；设备有 `/system/bin/su` |
| 通知监听一直读不到（`count=0`） | 重装 APK 后 ColorOS 不会自动重绑 listener | `cmd notification disallow_listener ev.buan/ev.buan.EvNotificationListener` → `allow_listener`（同参数） |
| Java 报 `libjli.so` 找不到 | 容器 `/proc` 没挂 | 回到第 3 节重新 `mount --bind` |

---

## 8. 故障对照表（本轮真实踩到的）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `sdk.dir=/root/android-sdk` 不存在 | `local.properties` 指向了不存在的路径 | 改成 `/opt/android-sdk`（该文件已被 gitignore） |
| 2 | 构建报 `classes.jar` 不存在 | `agent-browse` / `agent-device` 用文件依赖引用 `agent-api` 的类输出，却漏了 `dependsOn(:plugins:agent-api:bundleDebugClassesToCompileJar)` | 照兄弟模块补上顺序边（commit `25b4734`） |
| 3 | 容器里 `unzip: command not found` | `/usr/bin/unzip` 是 dpkg 残留的**断链** | 解包按 `unzip → python3 → jar` 回退 |
| 4 | Kotlin DSL 里 `java.util.zip.ZipFile` 报 `Unresolved reference 'util'` | 脚本里 `java` 被 Gradle 的 java 扩展遮蔽 | 在 `.kts` 顶部 `import java.util.zip.ZipFile` |
| 5 | 改了代码、构建成功，但 APK 里没有新类 | `cp` 到了 `host/src/main/` 而不是 `host/src/main/kotlin/ev/buan/` | 放对目录；用 `strings classes*.dex | grep 类名` 兜底 |
| 6 | `ctx.get(Key)` 抛 `cannot get property … without inject` | Cordis 要求先把 key 声明进 `dependencies(...)`，`get` 才是"可选" | 声明后 `get` 返回 null 而不抛 |
| 7 | `network: "unknown"` | 缺 `ACCESS_NETWORK_STATE`，`ConnectivityManager` 抛异常被吞 | manifest 补权限 |
| 8 | `app_list` 永远返回空 | API 30+ 包可见性：没声明 `<queries>` | manifest 加 `MAIN/LAUNCHER` 的 queries |
| 9 | 插件激活了但没有工具 | `apply()` 里 `lookup(ToolRegistry)` 拿到 null 就静默 return | 短轮询（3 s）+ 注册/撤回都打日志 |
| 10 | 安装插件把调用方自己搞崩（`JobCancellationException`） | 安装走了 `resync()`，它会把"这轮没扫到"的插件停掉，而调用方正是其中一颗 | 安装只扫 + 只激活新装的那一颗 |
| 11 | 卸载后私有副本删不掉 | `files/mods` 是 `r-x`（防就地篡改），unlink 需要**目录**可写 | 临时 `setWritable(true)` → 删 → `setReadOnly()` |
| 12 | `kotlin.test` 在 Android 单测里 `Unresolved reference` | 默认 classpath 没有 kotlin-test | 用 JUnit4 断言；注意 `assertTrue(message, condition)` 是**消息在前** |

---

## 9. 设备端锻造（另一条编译链路）

上面写的是"在构建机上编 EV"。另有一条链路是**EV 自己编插件**：

- 能力入口：`:plugins:forge`（工具面）+ `:plugins:forge-compiler`（编译器载荷）
- 设计决策：[ADR-0004](adr/0004-on-device-forge.md)
- 原理与 10 个 ART 兼容坑：[research/on-device-compiler.md](research/on-device-compiler.md)

载荷（约 26 MB / 14 dex）不进仓库，用脚本生成：

```sh
# Tier 1：从已验证的探针产物搬运（当前可用）
scripts/forge-payload.sh --from-probe /sdcard/EV/mods/compileprobe.jar \
  --plugin-api <plugin-api 的 classes.jar> \
  --out plugins/forge-compiler/payload

# 然后照常打包
:plugins:forge-compiler:bundlePlugin
```

Gradle 里的 `prepareCompilerPayload` 会自动带上 `--plugin-api`（取当前构建产物），
避免用探针里那份**冻结在 2026-10-27** 的旧契约——否则编出来的插件看不到后续新增的接口。

`--from-source`（从 Kotlin 编译器 jar 重新 dex 化 + ASM 打补丁）**尚未实现**，
缺什么见 ADR-0004 待办 1。

---

## 10. 收工前检查清单

- [ ] 改动只落在 `:host` / `:plugin-api` / `:ui-kit` / `:plugins:*`，没动内核五个目录
- [ ] 插件跑了 `testDebugUnitTest`，不是只说"编译通过"
- [ ] 改了 `plugin-api` 或 `ui-kit` → 跑过 `apiSnapshot` 并提交 `api/*.txt`
- [ ] 产物用 `strings classes*.dex | grep <新符号>` 验过，不是只看 md5
- [ ] jar 与同名 json **成对**推进 `/sdcard/EV/mods`
- [ ] 新哈希已在 `ev.unsigned.xml` 里批准，且启动过两次
- [ ] 设备上验证过（日志或工具返回），并在汇报里写清"未实测"的部分

---

## 11. 无障碍与通知：两项需要用户手动授权的系统能力

这两项不在运行时权限里，只能由用户在系统设置里开启。它们的授权状态**会在 force-stop 后丢失**
（ColorOS 行为），所以每次重装或强停应用都要重新授权。

### 11.1 通知使用权（`notify_list`）

```sh
settings get secure enabled_notification_listeners      # 看是否已授权
```

没授权时 `notify_list` 返回 `granted=false`。授权后**需要重启一次进程**才会绑定；
如果重装 APK 后仍读不到，用下面两条强制重绑（同一组件先 disallow 再 allow）：

```sh
cmd notification disallow_listener ev.buan/ev.buan.EvNotificationListener
cmd notification allow_listener    ev.buan/ev.buan.EvNotificationListener
```

### 11.2 无障碍（`screen_*`）

```sh
settings get secure enabled_accessibility_services       # 看是否已授权
```

开启（把 EV 追加到已有列表，不要覆盖别人的）：

```sh
CUR=$(settings get secure enabled_accessibility_services)
settings put secure enabled_accessibility_services "$CUR:ev.buan/ev.buan.EvAccessibilityService"
settings put secure accessibility_enabled 1
```

验证绑定成功（要看 **Bound services**，不是 Enabled services）：

```sh
dumpsys accessibility | sed -n '/Bound services/,/Enabled services/p' | grep 'EV 屏幕操作'
```

> 容易误判：`Bound services` 里显示的是服务的 **label**（"EV 屏幕操作"），不是包名。
> 用包名 grep 会以为没连上。

### 11.3 顺序很关键

`force-stop` 会移除无障碍授权，所以**不能**先授权再启动：

```
am force-stop ev.buan
am start -W -n ev.buan/ev.buan.MainActivity   # 先让进程起来
settings put secure enabled_accessibility_services "...ev.buan/ev.buan.EvAccessibilityService"
```

而且无障碍服务由系统异步绑定，可能晚于插件 `apply()`。依赖它的自检代码要轮询等待
（实测 0–3 秒），不能假设"应用起来了无障碍就绪了"。
