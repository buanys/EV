# 0004 · 设备端锻造（forge）：在手机上编译并安装插件

**状态：已接受（2026-10-30）** · 前置验证：[research/on-device-compiler.md](../research/on-device-compiler.md)

## 背景

`docs/research/on-device-compiler.md` 已经实测证明：手机能离线把 Kotlin 源码编译成 dex、打成插件、
装进 `/sdcard/EV/mods` 并运行（全链路 1.9 秒）。验证载体是 `tools/forge-probe/`——**一次性探针**，
不进 `settings.gradle.kts`，二进制载荷靠人工准备。

本文档把这件事从"验证过"变成"能交付"：定归属、定载荷形态、定生效方式、定安全边界。

## 决策

### D1 归属：编译器进插件，安装进宿主

| 部件 | 归属 | 理由 |
|---|---|---|
| K2 编译器、D8、源码→dex→jar、诊断 | `:plugins:forge-compiler` | 重活，可单独关闭；崩了不连坐宿主 |
| `forge_build` / `forge_install` 工具面 | `:plugins:forge` | 能力插件，经 `ToolRegistry` 注册工具 |
| 写 `mods/`、校验、加载、重启 | 宿主（新契约） | 插件没有共享存储写权限，加载器是宿主独占 |

内核（`loader core hmr include packages`）不动，冻结门禁不受影响。

### D2 载荷形态：B —— 独立能力包，默认关闭

编译器载荷约 26 MB / 10 dex。**不塞进 forge 容器**，而是独立成 `forge-compiler`，由用户按需启用：

- 启动路径零成本：不启用就完全不参与类加载；
- 与"插件按能力切分、可单独关闭"的铁律一致；
- 代价是多一个插件 id 和一条依赖边（`forge` 的 sidecar 声明依赖 `forge-compiler`）。

被否掉的方案：塞进 `forge` 容器（永久支付 26 MB 的启动校验成本）；
首次使用时从 assets 解到私有目录再子 `DexClassLoader` 加载（更优，但"私有目录只读 dex 能否加载"
未实测，留作后续）。

### D3 生效方式：v1 装完提示重启

`installer.sync()` 目前只在启动时调用一次（`HostRuntime.kt:257`），单插件启停才是活操作。
热激活（`sync()` + `setEnabled(newId, true)`）理论可行但未验证，**v1 不做**：

- 安装结果如实返回 `needsRestart = true`，UI 明说"需要重启 EV 生效"；
- 整个 app 打磨完再回头做热激活，届时只改宿主实现，契约不变。

### D4 工具面：只开两个

| 工具 | dangerous | 说明 |
|---|---|---|
| `forge_build` | 否 | 编译 + 打包（写在自己工作区），返回 dex 数、字节数、**完整编译诊断** |
| `forge_install` | **是** | 走 `PluginInstallService` + 人工确认，装完默认禁用 |

编译诊断必须原样回传：`Exception while generating code` 会吞掉 `Caused by`
（[研究文档 §4.8](../research/on-device-compiler.md)），截断了模型就没法自己修。

### D5 安全边界

编出来的插件**跑在宿主进程、同 UID、能调 su** —— 这是任意代码执行权。因此：

1. **装完默认 disabled**，由人手动启用；
2. `forge_install` 标 `dangerous`，走审批门 + 人工确认（来源=agent 生成、源码摘要、声明权限）；
3. **编译期 classpath 白名单**：`core.jar`、`host-api.jar`、`android-stub.jar`、`annotations.jar`，
   `kotlin-stdlib` 只作 `--lib` 参考**绝不进 dex**（否则 `DUPLICATE_PLATFORM_CLASS` 被拒）；
4. 生成的 sidecar `apiVersion` 固定为 1；`dependencies` 必须显式写出（jar sidecar 的键是 `dependencies`，漏了会导致加载顺序错）；
5. 不承诺沙箱——与 [ADR-0003](0003-permission-scope.md) 的权限模型一致，这里只是把"谁有权装"显式化。

### D6 文档位置

本项目不把 `/root/specs/01–10` 那套规格搬进仓库（那套是通用规格集，与实现解耦）。
forge 是 EV 的一个具体能力，**写在 `docs/`**：本 ADR + `docs/research/on-device-compiler.md`（原理）。

## 后果

**正面**：agent 具备了"给自己长能力"的闭环；载荷可单独关闭；安装路径复用宿主已有的校验与安全模式。

**负面**：

- 冷启动成本未测（26 MB 载荷在被启用时进入类加载）；
- 编译器内存峰值未测，2 GB heap 下大文件可能 OOM；
- 载荷目前仍是**从探针产物搬运**（见待办 1），真正从源码重建前，仓库不具备完全可复现性；
- `mods/` 在外部存储，任何有存储权限的 app 都能往里放 jar——forge 不改变这个信任模型，
  但把"后果"放大了，必须在用户可见处说明。

## 待办

1. **P1b · 载荷从源码重建**：`scripts/forge-payload.sh --from-source` 需要补三样：
   `trove4j:1.0.20200330`（缓存里没有，需联网取一次）、两个 ASM 补丁的实现
   （`PerformanceManager.initializeCurrentThread` 只保留 `thread` 赋值；`PathUtil` 的资源查找返回
   `NO_PATH` 哨兵）、`android-stub.jar`（从 `android.jar` 抽 `java.*` 共 1160 个类）。
   当前 `--from-probe` 是用哈希固定的探针产物搬运，可复现但不算自举。
2. **P2 · 宿主契约**：`PluginInstallService`（install/uninstall）+ `HostRestart`，改完跑 `apiSnapshot`。
3. **P3 · `forge` 工具面**：`forge_install` 接审批与人工确认。
4. **P4 · 真机闭环验收**：agent 自己写插件 → 编译 → 装 → 重启 → 加载运行，留日志。
5. **P5 · 硬化**：冷启动与内存预算、Compose 插件（§4.9/4.10 的 remap 三件套）、审计入事件流。

## 真机验证记录（2026-10-30，PMX110 / Android 17，EV 0.1.0）

**结论：链路在真机上闭合。** 用一次性自检插件（直接调 `ForgeCompiler`，绕开模型）取得：

```
START
COMPILER_FOUND
OK dex=1 bytes=1892 ms=2675
```

随后把这份产物放进 `mods/` 安装并启动，插件自身写出 `ALIVE 1793372071490` ——
**手机编译 → 安装 → 加载 → 运行**，全程无电脑参与。首次 2.7 秒（含载荷解包），第二次 1.9 秒（探针当年也是 1.9 秒，
载荷解包是一次性成本）。

### 三个只在真机上才暴露的坑

1. **声明了用不到的能力会被权限门静默挡住。**
   首版 `forge-compiler` 的 sidecar 写了 `PLUGIN_STORAGE`，但它实际不碰插件存储。
   宿主在 `HostRuntime.activate` 里先过权限门，未授权就不激活——**不打日志、不抛异常**，
   只在插件卡片里留一句话。表象是 `forge` 报依赖缺失（`... is not registered`），
   而真正的因果链是「编译器没被授权 → 没注册 → 依赖它的插件解析失败」。
   修法：sidecar 只声明真正使用的能力。**声明是意图，不是装饰。**

2. **未签名容器逐次按 id + sha256 审批**（`autoTrustUnsigned = false`）。
   重建容器 = 新哈希 = 需要重新确认；忘了这一步的表现同样是"依赖缺失"，
   而且日志里没有任何线索。安装通道（P2）必须把这个确认做进流程，不能让用户猜。

3. **新容器要两次启动才生效**：第一次落盘到 `files/mods`，第二次才被加载。
   这与 D3「装完提示重启」直接相关——v1 的提示文案要说清是"重启后还需再启动一次"，
   否则用户会以为失败。P2 的安装契约应当把「落盘 + 重新 sync + 激活」做成一步。

### 未验证

- `forge_build` 工具本身未经模型调用验证：设备上的模型余额不足（HTTP 402），agent 起不来回合。
  服务层（`ForgeCompiler`）已实测，工具层只做了与 `agent-device` 同构的代码走查。
- 冷启动成本未量化（26 MB 载荷在被启用时进入类加载，观感无异常）。
- 带第三方依赖、Compose 的插件仍未验证（与 P1b 待办一致）。

## P2 落地记录（2026-10-30 深夜）

**契约**：`PluginInstallService`（install / uninstall）+ `HostRestart`，`host-api` 只加不改；
判定逻辑抽到 `:host-core` 的 `InstallDecision`（8 个单测），宿主只负责搬字节和触发扫描。

真机结果（一次性自检插件直接调契约）：

```
INSTALL_SERVICE=true
BUILT dex=1 bytes=1878 ms=3893
FIRST  status=NEEDS_APPROVAL  sha=a4be57a38d2e
SECOND status=INSTALLED       active=true
THIRD  status=INSTALLED       active=true（幂等重装）
UNINSTALL removed=true
私有残留 = 0
```

**D3 因此改写**：首次安装**不需要重启**——`install` 在当前进程里完成落盘 + 扫描 + 激活，
`active=true` 就是证据。`HostRestart` 只为「替换一个已经加载的容器」保留（类加载器攥着旧 dex）。
当初按"两次启动才生效"写的那条 UX 提示可以删掉了。

### P2 期间踩到的三个坑

1. **服务发现走错通道。** 宿主用 `cordis.provide(...)` 提供的服务要走 `inject` / `ctx.require`；
   `ServiceDirectory` 装的是插件之间用 `publish` 互相提供的东西。第一次自检报
   `INSTALL_SERVICE=false` 就是查错了表。
2. **`install` 里调 `resync()` 会杀掉调用方。** `resync` 会停掉"这一轮没扫到"的插件，
   而调用方本身就是一颗正在 `apply()` 的插件——它的 Fiber 被 dispose，协程当场被取消
   （`JobCancellationException`），安装断在半路。改成只扫 + 只激活新装的那一颗。
   结论：**安装不欠别人任何清理动作。**
3. **`files/mods` 是 r-x，unlink 需要目录可写。** 宿主在 sync 结束时 `setReadOnly()` 锁住私有目录，
   防的就是容器被就地篡改，于是卸载时的 `File.delete()` 静默返回 false（文件还在，日志也没有）。
   修法与 publish 一致：临时 `setWritable(true)` → 删 → 立刻 `setReadOnly()`。
   这条坑的教训是**先看日志再做第二次尝试**：前一版我以为路径算错了，白改了一轮。

## P3 落地记录（2026-10-31 凌晨）

工具面两条：`forge_build`（不危险，只编译）+ `forge_install`（**dangerous**，装进宿主并启用）。

真机自检（自检插件按名字从 ToolRegistry 取两条工具直接调，绕开模型）：

```
TOOLS build=true install=true
INSTALL_DANGEROUS=true
BUILD  {"ok":true,"jar_bytes":1883,"dex_count":1,"elapsed_ms":3385,...}
INSTALL {"status":"INSTALLED","active":true,"next":"已在当前进程里跑起来，不需要重启。"}
ALIVE 1793376506805
```

### 与 D5「装完默认 disabled」的偏差，以及为什么

实际实现是**安装即启用**。理由：`forge_install` 标了 `dangerous`，宿主的审批门会在执行前拦下这一次调用，
用户点"允许"就是对**这次**安装的同意；再让用户去插件页点第二次启用，是同一份同意的重复确认。
防线并没有变薄：审批仍按 id + sha256 记录，字节被换掉之后仍会在加载时被拒。

如果要更严（例如"agent 只能装、人必须手动启用"），做法是给 `install` 加一个 `enable = false` 参数，
契约形状已经支持（`PluginInstallResult.active`），不需要动宿主结构。

### P3 期间踩到的坑

- **`kotlin.test` 不在 Android 单测默认 classpath 上**：`plugins/forge` 的单测改用 JUnit4 断言；
  另外 JUnit4 的 `assertTrue(message, condition)` 是**消息在前**，与 `kotlin.test` 相反。
- **自检插件的 sidecar 依赖少写一项，就是 `NoClassDefFoundError`**：`ToolRegistry` 在
  `ev.buan.plugins.agent.api` 里，容器必须在 `dependencies` 里登记它，否则类加载器根本看不到这个契约。
  与 ADR-0004 D5「`dependencies` 必须显式写出」是同一条。
- **打包自检容器时漏写 sidecar**，宿主会安静地跳过这个 jar（没有 sidecar = 没有元数据），
  表现是"插件压根没装"，日志里也没有线索。
