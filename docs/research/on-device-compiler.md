# 设备端编译 Kotlin 的可行性验证

记录一次完整验证：**Android 手机在无电脑参与的情况下，把一段 Kotlin 源码编译成 dex、打包成插件、装进 EV 并运行**。

结论：**可行**。全链路 1.9 秒，已实测通过。

---

## 1. 结论与证据

2026-10，探针插件 `compileprobe` 在设备上执行：

```
COMPILE_EXIT=OK
CLASSES=1
DEX_COUNT=1
PLUGIN_JAR=/sdcard/EV/mods/taotie-forged.jar  2215 字节
PLUGIN_JSON=/sdcard/EV/mods/taotie-forged.json
TOTAL_MS=1923
```

重启 EV 后，该插件被正常加载并执行，写出：

```
FORGED_PLUGIN_ALIVE at 1793060494377
```

即：**手机 → 生成 Kotlin → 编译 → dex → 打包 → 装进自己 → 运行**，全程离线。

---

## 2. 链路

```
Kotlin 源码
  ├─ K2JVMCompiler（官方编译器，跑在 ART 上）→ .class
  ├─ D8.run(D8Command)（d8，跑在 ART 上）     → classes.dex
  ├─ ZipOutputStream                         → plugin.jar
  └─ 写入 /sdcard/EV/mods/                    → 重启 EV 自动加载
```

**关键**：生成 dex 后**不要自己 DexClassLoader**。Android 禁止从可写目录加载 dex（`SecurityException: Writable dex file ... is not allowed`），且把插件搬进私有目录、设只读、加载，本来就是这个 App 的职责。

---

## 3. 承载插件的结构

一个 26 MB 的 EV 插件，10 个 dex + 若干资源文件。

| dex | 内容 | 大小 |
|---|---|---|
| `classes.dex` | 工具入口 + 两个 patch 过的类 | ~51 KB |
| `classes2..6.dex` | `kotlin-compiler-embeddable` 2.4.20 | 54 MB |
| `classes7.dex` | `trove4j` | 428 KB |
| `classes8..9.dex` | `d8`（build-tools 37.0.0） | 11 MB |
| `classes10.dex` | `org.jetbrains:annotations` | 13 KB |
| `classes11.dex` | Compose 编译器插件（**重定位后**的 `ev.buan.taotie.cplugin.*`） | 1.1 MB |

资源文件（打进 jar，运行时解压到磁盘）：

| 文件 | 用途 |
|---|---|
| `kotlin-stdlib.jar` | 编译期 classpath（1.8 MB） |
| `android-stub.jar` | 从 `android.jar` 抽出的 1160 个 `java.*` 类（2.3 MB），编译期 classpath |
| `core.jar` / `plugin-api.jar` | 编插件时解析 `org.cordis.*`（625 KB） |
| `annotations.jar` | 编译期 classpath 里的注解签名 |
| `META-INF/compiler.version` | 内容 `2.4.20`，缺了编译器启动即崩 |
| `META-INF/services/*` | 5 个 ServiceLoader 描述文件，缺了 `BuiltInsLoader` 找不到 |

**不要打包 `kotlin-stdlib` 成 dex**：EV 会以 `DUPLICATE_PLATFORM_CLASS` 拒绝（1739 个 `kotlin.*` 类与宿主重复）。`annotations` 和 `trove4j` 不在共享包清单里，可以安全打包。

---

## 4. 八个坑

按踩坑顺序。每个都是从堆栈一路挖下去的。

### 4.1 `java.lang.management` 在 Android 上不存在

```
NoClassDefFoundError: Failed resolution of: Ljava/lang/management/ManagementFactory;
  at PerformanceManager.initializeCurrentThread
```

Android 没有 JMX。编译器用它统计线程 CPU 时间。

**修法**：用 ASM 改写 `org/jetbrains/kotlin/util/PerformanceManager.class`。

**注意**：不能把 `initializeCurrentThread` 整个清空——它同时负责 `thread = Thread.currentThread()`，清空了会导致后面的 `ensureNotFinalizedAndSameThread` 抛 `lateinit property thread has not been initialized`。正确做法是**只保留 `thread` 赋值，砍掉后面的 MXBean 部分**：

```
ALOAD 0
INVOKESTATIC java/lang/Thread.currentThread
PUTFIELD  PerformanceManager.thread
RETURN
```

另外 `enableExtendedStats` / `notifyCompilationFinished` 可以整体清空（都是 void）。

### 4.2 `PathUtil` 找不到自己的 class 资源

```
IllegalStateException: Resource not found: /org/jetbrains/kotlin/utils/PathUtil.class
  at PathUtil.getResourcePathForClass
```

编译器用 `getResource()` 定位自己的 jar，但 dex 里没有 `.class` 资源。

**修法**：改写 `PathUtil.getResourcePathForClass` 直接返回静态字段 `NO_PATH`（该类本来就定义了这个哨兵值），不要让它抛异常。

### 4.3 找不到 JDK 路径

```
error: no class roots are found in the JDK path: /apex/com.android.art
```

**修法**：传 `-no-jdk`。不是代码问题，纯参数。

### 4.4 `gnu.trove` 缺失

```
NoClassDefFoundError: Lgnu/trove/TObjectHashingStrategy;
  at FileUtil.<clinit>
```

编译器依赖 trove4j 但不自带（IntelliJ-core 的依赖）。

**修法**：`org.jetbrains.intellij.deps:trove4j:1.0.20200330`（560 KB），dex 化后进插件。

⚠️ Maven 坐标是 `org.jetbrains.intellij.deps`，不是 `gnu.trove`——后者在中央仓库是 404。

### 4.5 `META-INF/compiler.version` 缺失

```
ExceptionInInitializerError
  at KotlinCompilerVersion.<clinit>
```

编译器启动时要读这个资源确认版本。

**修法**：在插件 jar 里放 `META-INF/compiler.version`，内容就是版本号（`2.4.20`）。`DexClassLoader` 能读 jar 内的资源，6 个字节解决，不用改代码。

### 4.6 `META-INF/services` 丢失

```
IllegalStateException: No BuiltInsLoader implementation was found.
```

重新打 jar 时把 `META-INF/services/` 排除了，`ServiceLoader` 找不到实现。

**修法**：把原 jar 里的 5 个 services 文件原样带过来。

### 4.7 `annotations` 只在 classpath 里不够

这个坑踩了两次，第二次才找对。

```
RuntimeException: Exception while generating code for <get-name>(): kotlin.String
Caused by: NoClassDefFoundError: Lorg/jetbrains/annotations/NotNull;
  at AnnotationCodegen.generateNullabilityAnnotation
Caused by: ClassNotFoundException: org.jetbrains.annotations.NotNull
  at org.cordis.loader.IsolatedPluginClassLoader.loadClass
```

**根因**：`annotations.jar` 作为 classpath 只满足"**读**"（编译器解析签名时能看到），但生成 `@NotNull` 注解时编译器要**加载这个类**。ART 上插件类加载器只认 dex，jar 躺在文件夹里它看不见。

**修法**：`annotations` 同时提供两种形态——`annotations.jar` 作编译期 classpath **以及** dex 化后打进插件（第 10 个 dex）。

**这条是通用规律**：「编译器依赖的东西」必须**能被加载**，不只是**能被读到**。

### 4.8 最开始的假象：`Exception while generating code` 会吞掉原因

第一次遇到时，外层只显示 `Exception while generating code`，看不出任何有用信息。真正的 `Caused by` 被截断了。

**教训**：拿不到 `Caused by` 就别猜。探针的日志要留足够长度，或单独抽取 `Caused by` 行；完整 stderr 落盘到文件。

---

### 4.9 Compose 编译器插件：`-Xplugin=` 在 ART 上加载不了 jar 里的类

```
ClassNotFoundException: androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
  at ServiceLoaderLite.loadImplementations
Caused by: ClassCastException: UnsupportedOperationException cannot be cast to ClassNotFoundException
```

`-Xplugin=某jar` 依赖 `URLClassLoader` 从 jar 里加载 `.class`——**这是 JVM 才有的能力**。ART 的 ClassLoader 只认 dex。那个 `ClassCastException` 是 ART 特有的假象，别被它带偏。

**修法**（CodeAssist 同款）：jar 里只留 `META-INF/services` 描述符给 `-Xplugin` 读，类本体 dex 化打进插件，`Class.forName` 走父加载器从 dex 里拿到。

### 4.10 Compose 编译器插件撞共享包前缀

`ModuleLoader` 判断宿主类用的是**前缀匹配**：

```kotlin
if (sharedPackages.any { name == it || name.startsWith("$it.") })
```

`androidx.compose` 是共享包 → `androidx.compose.compiler.plugins.kotlin.*`（编译器插件，251 个类）全部被 `DUPLICATE_PLATFORM_CLASS` 拒掉，尽管宿主根本没有这些类。

**修法**：ASM `ClassRemapper` 把 `androidx/compose/compiler` 整体重定位到 `ev/buan/taotie/cplugin`（270 个类 + 4 个 services 文件里的类名同步改）。改完本地验证：编出的 `@Composable` 产物里有 `Composer.startRestartGroup` 调用，证明插件仍然生效。

**通用结论**：任何想带上设备的 Kotlin 编译器插件，都要走「services 留 jar + 类体 dex 化 + 包名避开共享前缀」三件套。

## 5. 验收标准（重要）

**不要把"编译一个 `Counter` 成功"当成验证通过。**

`class Counter { fun add(a: Int, b: Int) = a + b }` 这类不实现任何接口、只用基本类型的类，走不到注解生成、走不到接口覆盖，**编过了也不代表编译器能在设备上干活**。

正确的验收标准：**能编出一个 EV 真正能装、能跑的插件**——即一个 `Plugin<Unit>` + `AgentSurface` 的实现，装进 `mods/` 后重启能被加载并执行。

---

## 6. 工具形态

- 编译器：`kotlin-compiler-embeddable` 2.4.20（Apache 2.0），`K2JVMCompiler().exec(...)`，**不要用 `main()`**
- d8：build-tools 37.0.0 的 `d8.jar`（Apache 2.0），**用 `D8.run(D8Command)`，不要用 `D8.main()`**——后者会 `System.exit`，直接杀掉宿主进程
- 字节码改写：ASM 9.9 + asm-tree
- 编译器可整包 dex 化：`d8` 处理 57 MB 的编译器 jar 零 missing class

---

## 7. 尚未验证

- ~~Compose 插件~~ **已验证（2026-10-27）**：`@Composable` 在设备上编译 → dex → 打包 → EV 加载运行全通过。前提是编译器插件 dex 化 + 包名重定位（见 4.9/4.10）。**未验证的**：带真实 UI 的 Compose 插件（布局、状态、miuix 组件）
- **复杂项目**：目前只验证了单文件、无第三方依赖的插件
- **增量编译**：每次全量编译，没有利用 K2 的增量能力
- **编译耗时随代码量增长的情况**：1.9 秒是 20 行源码的数据

---

## 8. 相关

- `docs/architecture.md` — 插件模型
- `docs/extension-points.md` — 插件契约
- CodeAssist（GPL-3.0）：本方案的架构参考来源，它的组件选择（官方工具链、非 embeddable 的历史取舍、mapping buffer 警告）与本次验证结论一致。**代码不可复制**，做法可参考。
- SKotch（Rust 写的 Kotlin 编译器）：评估后放弃。它的 lowering 是穷举式（`typed.rs` 6.7 万行、251 个 bail 点、按 `PropertyWithSafeAccessElvis` 这类组合命名），对真实代码 69% 的函数静默产出空字节码且退出码为 0。**静默失败比编译错误更危险。**
