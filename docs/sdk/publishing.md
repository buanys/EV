> **2026-11-10 起：这就是唯一形态。**
> 宿主仓库（`/workspace/EV`）里已经**没有任何插件模块**，构建只产出宿主。
> 所有正式版插件住在独立工程 `/workspace/ev-plugins`，一个模块一个插件。
> 本文下面的内容不再是"可选方案"，而是插件的实际住处与做法。

# 单独开一个 `ev-*` 插件工程

> 目标：插件能**脱离 EV 仓库打开、独立编译**，仍然对着宿主的真实契约。
> 结论先说：**用 Gradle 复合构建（`includeBuild`），不要用发布坐标。**
> 2026-11-10 实测通过。

## 1. 骨架（三段，可直接复制）

**`settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories { google(); mavenCentral() }
}

rootProject.name = "ev-plugins"

// 关键一行：不发布、不下载 aar，直接把 EV 的模块当依赖替换进来。
includeBuild("/workspace/EV")

include(":ev-ping")
```

**`build.gradle.kts`（根）**

```kotlin
plugins {
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
```

**`ev-ping/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")   // 不写 version
}

android {
    namespace = "ev.buan.plugin.ping"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 只用坐标，不写 project(":host-api")——这就是「单独开插件」要的形状。
    compileOnly("io.github.buanys:host-api:0.0.1-SNAPSHOT")
    compileOnly("io.github.buanys:loader:0.0.1-SNAPSHOT")
}
```

坐标里的版本号写什么都行（`includeBuild` 按 `group:name` 替换，不看版本），
写一个和 EV 默认值一致的最不容易让人困惑。

## 2. 两个必踩的坑

### 坑一：AGP 9 不允许再套 `kotlin.android`

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

AGP 9 自带 Kotlin 编译支持。子模块只写 `id("com.android.library")` 就够了，
多写一行直接构建失败。

### 坑二：根构建不钉 Kotlin 版本，元数据就对不上

```
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.4.0, expected version is 2.2.0.
```

AGP 9 虽然自带 Kotlin，但用的是**构建脚本 classpath 上能拿到的那个 KGP 版本**。
classpath 上没有 KGP 时它回退到内置的 2.2.0，而 EV 用 2.4.20 编译，
于是对 `host-api` 的 classes.jar 报元数据不兼容。

修法就是根构建里那两行 `apply false`——**写 `apply false` 也有用**，它的作用是
把 KGP 放上 classpath，而不是应用它。

## 3. 为什么不用 mavenLocal

仓库里确实配了发布（`io.github.buanys`，版本由 `-PevSdkVersion` 控制），
但**它从来没在可复现性门禁下跑通过**。2026-11-10 实测：

```
$ gradle :core:publishToMavenLocal :loader:publishToMavenLocal :host-api:publishToMavenLocal
> Dependency verification failed for configuration ':loader:detachedConfigurationN'
  34 artifacts failed verification: dokka-core-2.2.0.jar, jackson-*,
  kotlin-reflect-2.0.21.jar, woodstox-core-6.5.1.jar, ...
```

原因链：vanniktech 的发布插件会为 publication 生成 javadoc → 拉 dokka →
带出一整条 jackson/woodstox/kotlin-reflect 依赖，而这些都没写进
`gradle/verification-metadata.xml`（那是一份 810 组件、482 KB 的校验和清单，
由「可复现性三件套」维护）。

**不为它放行这批 artifact** 是 ADR-0011 的决定：javadoc jar 对插件毫无用处，
为它扩大受信依赖面是拿安全边界换一个没人用的产物。

### 真要解开发布，有两条路（都需要决策，不是顺手能做）

1. **扩白名单**：给那 34 个 artifact 补校验和进 `verification-metadata.xml`。
   代价是受信依赖面变大，且以后每次升级 dokka 都要再补一轮。
2. **给 SDK 单独走轻量发布**：把 `:host-api` / `:ui-kit` 从 vanniktech 的
   `publishableModules` 里摘出来，改用 Gradle 内置 `maven-publish`，不生成 javadoc。
   代价是要单独维护一套 POM 元数据（现在的 POM 写的是上游 Cordis 的作者信息，本来也不对）。

## 4. 复合构建的边界

能做的：

- 插件工程独立打开、独立构建、独立开 IDE
- 编译期拿到**真实的**宿主契约，不是复制粘贴的副本
- 改了宿主契约，插件下次编译立刻报错——这就是要的效果

做不到的：

- **发给别人**。对方没有 `/workspace/EV` 就编不了，必须回到发布坐标（见第 3 节）
- **插件之间的相互编译依赖**。插件之间只能靠共享契约 + `ServiceDirectory` 在运行时对接，
  这条在 `plugins/_template` 的注释里也写着

## 5. 实测记录

| 日期 | 结果 | 证据 |
|---|---|---|
| 2026-11-10 | ✅ 独立工程编译通过 | `/workspace/ev-plugin-smoke`，`gradle :ev-ping:compileDebugKotlin` BUILD SUCCESSFUL |

冒烟工程保留在那里，随时可以重跑。它引用两侧的类型（`org.cordis.Plugin` 来自 `:loader`，
`ManifestProvider` / `PluginManifest` 来自 `:host-api`），两者都解析成功才算通。
