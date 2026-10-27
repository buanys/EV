# forge-probe —— 设备端编译探针

一次性验证工具，用来复现 [设备端编译 Kotlin](../../docs/research/on-device-compiler.md) 的结论。

**这不是正式插件**，是不进 `settings.gradle.kts` 的独立探针：编译成一个巨型 EV 插件（约 26 MB / 10 个 dex），装进 `mods/` 后由 EV 自身加载，在 `apply()` 里跑完整链路。

## 它做什么

```
生成 Kotlin 源码 → K2JVMCompiler → .class → D8.run → classes.dex
  → 打包 jar + json → 写进 /sdcard/EV/mods/ → （重启 EV 后加载）
```

日志写在 `/sdcard/EV/compileprobe.txt`，编译器完整 stderr 在 `/sdcard/EV/compileprobe/compiler-stderr.txt`。

## 怎么打这个包

`build.sh` 记录完整流程。关键点：

- **dex 顺序**：`classes.dex` 放探针自身 + 两个 patch 过的编译器类（覆盖后面的大包），`classes2..6` 编译器，`classes7` trove4j，`classes8..9` d8，`classes10` annotations
- **资源**：`core.jar`、`plugin-api.jar`、`kotlin-stdlib.jar`、`android-stub.jar`、`annotations.jar`、`META-INF/compiler.version`、`META-INF/services/*`
- **绝对不能**把 `kotlin-stdlib` 打成 dex——EV 会以 `DUPLICATE_PLATFORM_CLASS` 拒绝

## 前置产物

本目录不含二进制，需要自己准备（见 `build.sh` 顶部注释）：

| 产物 | 来源 |
|---|---|
| `cdex/` | `kotlin-compiler-embeddable` dex 化（含 patch 过的 PerformanceManager、PathUtil） |
| `tdex/` | `trove4j` dex 化 |
| `ddex/` | build-tools 的 `d8.jar` dex 化 |
| `anndex/` | `org.jetbrains:annotations` dex 化 |
| `patchcls/` | ASM 改写后的两个编译器类 |
| `android-stub.jar` | 从 `android.jar` 抽 `java.*` 共 1160 个类 |

## 已知限制

只验证了单文件、无第三方依赖、不含 Compose 的插件。Compose 编译需要额外的编译器插件，未测。
