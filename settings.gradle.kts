pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Kotlin/JS registers the official Node distribution Ivy repository for
    // its test runtime, so project-level toolchain repositories must be allowed.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EV"

// 内核：vendor 到 third_party/cordis，版本与目录树哈希记在 third_party/cordis/KERNEL.lock。
// Gradle 项目名仍是 :core / :loader——目录归目录、坐标归坐标，几十处 project(":core")
// 不用跟着改；换目录只改下面两行。
include(":kernel", ":kernel:core", ":kernel:loader")
// Gradle 9 要求父项目也有实体目录；:kernel 本身没有构建脚本，只是坐标的一部分。
project(":kernel").projectDir = file("third_party/cordis")
project(":kernel:core").projectDir = file("third_party/cordis/core")
project(":kernel:loader").projectDir = file("third_party/cordis/loader")

// 只保留内核的 core 与 loader；utils/timer/logger-console/include/hmr 已归档到
// /workspace/EV-archive/2026-11-05，需要时再引回并写明理由（见 docs/newdocs/最高规则）。
// 打包 mods 还在用 packager，它依赖 packages，两者一起留。
include("packages", "packager")

// Android host runtime and the plugin contract it shares with plugins.
// 宿主拆成两层：host-runtime 放运行期（装配/运维/路由/恢复），host-app 放入口。
// 拆模块的顺序和拆类一致：先把不依赖宿主类型的那几块搬出去，一块一块来。
include(":host-runtime")
include(":ui-kit")
include(":host-api")
include(":host-app")
// Host-only logic (validation, per-plugin storage, crash bookkeeping, dependency resolution) that
// must not live in the published plugin contract or the design system. Plain Kotlin/JVM.
include(":host-core")
// 插件不在这个构建里。
//
// 2026-11-10：所有插件移出宿主仓库。实验版 17 个归档到
//   /workspace/EV-archive/2026-11-10-experimental-plugins/（只作参考，不参与构建）
// 正式版重写后的插件住在独立的 Gradle 工程 /workspace/ev-plugins，
// 用 includeBuild 引用本仓库、靠坐标拿到 :host-api 与 :loader。
// 所以本仓库只构建宿主；没有任何 :plugins:* 模块。

// 实验插件（hello, probe, coldapi, ping-api, ping-impl, ping-ui）已于 2026-10-25 删除：
// 它们不参与构建，唯一的作用是每次 ui-kit 契约变更后还要跟着重编一遍。
// frontend（旧聊天兜底）同期删除，ev-ui 是唯一界面插件。

// hello（模板闭环验证用）已于 2026-11-06 删除：验证目的已经达成，留着只是每次改动
// 都要跟着重编一次。_template 也已随实验版一起归档；新插件直接复制 plugins/base/ev-tools。

// 验证夹具 probe-remote 也搬去了插件工程（/workspace/ev-plugins/probe-remote）。
// 本仓库不再包含任何插件形状的模块——连夹具都不例外。
