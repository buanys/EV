# ADR-0002：插件接口（D2 = A）

- 状态：已采纳
- 日期：2026-10-24
- 决策编号：D2 = A
- 相关：[architecture.md](../architecture.md) 第 2、6 节、[ADR-0001](0001-kernel-consumption.md)、
  [ADR-0003](0003-permission-scope.md)

## 背景

插件需要三件事：生命周期（加载/启用/停用/释放）、依赖与注入、以及和宿主/其他插件通信。
Cordis 内核（`core`）已经提供了完整实现：`org.cordis.Plugin`、`Context`、`Fiber`、`ServiceKey`、
`Dependencies`、`EffectScope`。`loader` 的 Android 实现负责把插件 dex 装进独立的 child-first
class loader，并强校验入口类型：

```kotlin
// loader/src/androidMain/kotlin/org/cordis/loader/ModuleLoader.android.kt
require(instance is Plugin<*>) { ... }
```

宿主 `HostRuntime.activate` 直接执行 `cordis.plugin(instance, Unit)` 并等待 `fiber`
（[host/src/main/kotlin/ev/buan/HostRuntime.kt](../../host/src/main/kotlin/ev/buan/HostRuntime.kt)）。

问题是：SDK 收敛时要不要在插件和内核之间加一层 `EvPlugin`，让插件只看到 EV 自己的契约？

## 决策

**采用 D2 = A：承认 Cordis 就是插件运行时，插件直接实现 `org.cordis.Plugin`；不新增 `EvPlugin`
适配层。文档写清插件可见内核 API。**

具体边界：

- `:plugin-api` 只放**业务契约**：`AgentPage`、`AgentUi`、`AgentSurface`、`AppearanceService`、
  `HostNavigation`、`ServiceDirectory`、`HostPackages`、`PLUGIN_API_VERSION`。
  它对内核是 `compileOnly(project(":core"))`——公开签名里会出现 `ServiceKey`，但不向使用方导出内核。
- 插件模块 `compileOnly(project(":loader"))` + `compileOnly(project(":plugin-api"))`
  （`ping-api` 之外的插件还加 `:ui-kit`），入口实现 `Plugin<Unit>`；运行期 `org.cordis.*` 由宿主的共享前缀提供。
- 注入走内核语义：`override val inject = dependencies(...)`、`ctx.require(Key)`；
  需要**可选**依赖时用 `ServiceDirectory`（内核的强注入会让消费者一直等，见
  [plugin-api/src/main/kotlin/org/cordis/api/ServiceDirectory.kt](../../plugin-api/src/main/kotlin/org/cordis/api/ServiceDirectory.kt) 的注释）。
- 宿主不重复实现生命周期：加载、依赖等待、`apply: suspend`、`EffectScope` 清理都由内核 `Fiber` 负责。

## 代价与限制

- **SDK 无法隐藏内核**。`org.cordis` 是加载器内置共享前缀（`DEFAULT_SHARED_PACKAGES`），插件运行期
  必然拿得到 `Context`/`Fiber`/`ServiceKey` 等全部内核类型；拿掉就等于所有插件加载失败。
  所以「plugin-api 是完整 API 面」是不成立的表述，本文与 [architecture.md](../architecture.md) 都按
  「SDK = 业务契约，内核 API 另计」来写。
- **插件编译期需要 `:loader`**。`:plugin-api` 用 `compileOnly` 引用内核，不向下游导出 `:core`；
  插件为了拿到 `org.cordis.Plugin` 必须自己声明 `compileOnly(project(":loader"))`。这等于把内核
  放进每个插件的编译期可见面，SDK 的「收敛」只发生在模块归属上，不发生在可见性上。
- **契约与内核语义纠缠**。`AppearanceService`/`ServiceDirectory` 的 `Key` 直接是内核 `ServiceKey`，
  插件注入语法也来自内核；想换插件运行时不再只是换 SDK，而是重写插件。
- **没有中间层就没有兼容缓冲**。内核 API 变更（如 `AndroidModuleVerifier` 签名）会直接打到宿主；
  插件若用到内核内部类型，同样只能靠「同代编译」保证。`PLUGIN_API_VERSION = 1` 只覆盖业务契约，
  不覆盖内核面。
- **错误用法不会被挡**。插件作者可以 import `org.cordis.loader`、`org.cordis.hmr` 等内部实现
  （同在前缀下），宿主没有编译期或运行期拦截；只能靠评审与文档约束。

## 备选方案（未采纳）

- **新增 `EvPlugin` 适配层**：插件只实现 EV 自己的接口，由宿主适配到 `org.cordis.Plugin`。
  好处是能遮蔽内核、为将来换运行时留口子；代价是宿主必须重新实现生命周期、依赖等待、清理语义
  （或把内核的 `Fiber` 语义包一层再解释一遍），同一套概念出现两份，且插件仍会通过 `ServiceKey`
  等类型泄漏到内核。本轮没有足够收益支撑这份复杂度。

## 影响

- 新插件一律按 `org.cordis.Plugin` 写，不引入新的插件基类。
- `:plugin-api` 的新增内容必须是「宿主 ↔ 插件」的业务契约；宿主内部校验/存储等逻辑放到 `:host-core`（T13）。
- 文档、README、ADR 不得声称「插件只依赖 SDK」或「SDK 隔离了内核」。
