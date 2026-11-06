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

// 内核：vendor 到 third_party/cordis，版本与目录树哈希记在 third_party/cordis/KERNEL.lock。
// Gradle 项目名仍是 :core / :loader——目录归目录、坐标归坐标，几十处 project(":core")
// 不用跟着改；换目录只改下面两行。
include(":core", ":loader")
project(":core").projectDir = file("third_party/cordis/core")
project(":loader").projectDir = file("third_party/cordis/loader")

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
// 在构建内的插件，按"做什么"分四类（见 docs/newdocs/最高规则 §二）：
//   base        能力提供者（工具总线、存储、日志、设备、执行、屏幕）
//   feature     页面与行为（界面、主循环、浏览器、提示词、设置）
//   resource    数据/主题一类的叶子节点
//   integration 对接外部系统（模型服务、Web 门面、自编译工具链）
include(":plugins:feature:settings")
include(":plugins:feature:ev-ui")
include(":plugins:integration:agent-model")
include(":plugins:base:agent-tools")
include(":plugins:base:agent-memory")
include(":plugins:feature:agent-brain")
include(":plugins:feature:agent-prompt")
include(":plugins:base:agent-log")
include(":plugins:base:agent-device")
include(":plugins:base:agent-gui")
include(":plugins:feature:agent-browse")
include(":plugins:integration:agent-web")
include(":plugins:base:agent-shell")
include(":plugins:base:linux")
include(":plugins:integration:forge")
include(":plugins:integration:forge-compiler")
// 主题插件：全局视觉定义的唯一来源。默认不随仓库打包，装到 mods 即生效；
// 不喜欢它的人只换这一个插件，布局插件 ev-ui 不用动。
include(":plugins:resource:theme-default")

// 实验插件（hello, probe, coldapi, ping-api, ping-impl, ping-ui）已于 2026-10-25 删除：
// 它们不参与构建，唯一的作用是每次 ui-kit 契约变更后还要跟着重编一遍。
// frontend（旧聊天兜底）同期删除，ev-ui 是唯一界面插件。

// hello（模板闭环验证用）已于 2026-11-06 删除：验证目的已经达成，留着只是每次改动
// 都要跟着重编一次。_template 仍在仓库里，需要时复制一份改名即可。

// 受限档子进程的验证夹具：只有显式传 -PevTestPlugins 才进构建。
// 它不是产品的一部分（一个只会回答自己 pid 的工具），但也不是一次性的手工作品——
// 隔离那半条链路需要它可复现，否则下次改 binder 就没法验证。
if (providers.gradleProperty("evTestPlugins").isPresent) {
    include(":tools:probe-remote")
}
