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

rootProject.name = "cordis-kotlin"

include(
    "core",
    "utils",
    "timer",
    "logger-console",
    "loader",
    "include",
    "hmr",
    "packages",
    "packager",
)

// Android host runtime and the plugin contract it shares with plugins.
include(":ui-kit")
include(":plugin-api")
include(":host")
// Host-only logic (validation, per-plugin storage, crash bookkeeping, dependency resolution) that
// must not live in the published plugin contract or the design system. Plain Kotlin/JVM.
include(":host-core")
// 在构建内的插件：真实功能。
include(":plugins:settings")
include(":plugins:ev-ui")
include(":plugins:agent-api")
include(":plugins:agent-model")
include(":plugins:agent-tools")
include(":plugins:agent-memory")
include(":plugins:agent-brain")
include(":plugins:agent-prompt")
include(":plugins:agent-log")
include(":plugins:agent-device")
include(":plugins:agent-gui")
include(":plugins:agent-browse")
include(":plugins:agent-web")
include(":plugins:agent-shell")
include(":plugins:linux")
include(":plugins:forge")
include(":plugins:forge-compiler")
// 主题插件：全局视觉定义的唯一来源。默认不随仓库打包，装到 mods 即生效；
// 不喜欢它的人只换这一个插件，布局插件 ev-ui 不用动。
include(":plugins:theme-default")

// 实验插件（hello, probe, coldapi, ping-api, ping-impl, ping-ui）已于 2026-10-25 删除：
// 它们不参与构建，唯一的作用是每次 ui-kit 契约变更后还要跟着重编一遍。
// frontend（旧聊天兜底）同期删除，ev-ui 是唯一界面插件。
