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
include(":plugins:eta-ui")
include(":plugins:agent-api")
include(":plugins:agent-model")
include(":plugins:agent-tools")
include(":plugins:agent-memory")
include(":plugins:agent-brain")
include(":plugins:agent-web")
include(":plugins:agent-shell")

// 已摘除的实验插件（代码保留在 plugins/ 下，不参与构建；需要验证机制时再临时挂回）：
//   hello, probe, coldapi, ping-api, ping-impl, ping-ui
// frontend（旧聊天兜底）已于 2026-10-25 删除，eta-ui 是唯一界面插件。
