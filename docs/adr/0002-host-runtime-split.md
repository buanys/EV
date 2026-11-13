# ADR-0002：宿主拆成装配 / 运维 / 路由 / 生命周期

- 状态：已接受
- 日期：2026-11-06
- 相关：ADR-0001、`docs/newdocs/最高规则/ev-final-rules.md` §二

## 背景

`HostRuntime.kt` 1050 行、25 个字段，同时回答六个问题：

1. 这次启动加载哪些插件、什么顺序（装配）；
2. 插件能不能启用、信任、给权限、卸载、清存储（运维 / 裁决）；
3. 首页是哪个、某插件的页面与标题从哪来（路由）；
4. 服务怎么发布、句柄怎么撤（登记）；
5. 启动进度、失败列表、安全模式怎么表达（状态）；
6. 出问题怎么办（恢复）。

文件里还有 3 个状态 data class 和 1 个 `ServiceDirectory` 实现——它们和"宿主类"没有关系，只是没地方去。

## 决策

**先把一个类拆成四个职责，再把模块拆成 `host-app` / `host-runtime`。** 顺序不能反：先拆模块只是把 1050 行搬到另一个目录，边界仍然没有定义。

四个职责与状态归属：

| 职责 | 回答的问题 | 拥有哪些状态 | 不做什么 |
|---|---|---|---|
| **Composition** | 这次启动加载谁、按什么顺序、发布哪些服务 | `cordis`、`modules`、`trust`、`installer`、`active`、`pluginManifests`、`pluginSettings`、各 `EffectHandle` | 不决定"该不该启用"，那是 Ops |
| **Ops** | 谁能信任、启用、拿权限、卸载、清存储 | `approvals`、`permissions`、`capabilityTrust`、`preferences`、`crash` | 不自己激活插件，只发"需要重扫"的信号 |
| **Routing** | 首页是谁、页面/标题从哪来 | 首页偏好、`pageFor` / `uiFor` / `titleFor` 的查表 | 不做页面渲染（那是插件） |
| **Lifecycle** | 启动进度、失败、安全模式、快照 | `lifecycle`、`stateHolder`、`progressHolder`、`failures`、`syncErrors`、`pendingUnsigned`、`safeMode`、`snapshot()` | 不持有业务状态 |

四条不变量：

1. **Ops 只改存储，不改运行期 map**。改完之后由 Composition 重扫——否则"卸载了一个正在跑的插件"会有两条路径。
2. **Routing 只读**。页面归属变化必须经 Ops → Composition，不能在路由里改。
3. **Lifecycle 不 import 插件类型**。它只认识 `HostState` 这些自己的数据结构。
4. **快照是唯一出口**。UI 永远拿 `HostState`，不拿上面任何一个对象。

## 执行顺序（每一步都要能装机）

1. 状态模型搬出 `HostRuntime.kt`（`HostState.kt`），`ServiceDirectoryImpl` 单独成文件 —— 纯移动；
2. Routing 抽出 —— 5 个只读方法，依赖最少，用来验证拆法；
3. Ops 抽出 —— 8 个写操作，全部走"改存储 → 重扫"；
4. Lifecycle 抽出 —— 快照与失败/安全模式；
5. Composition 留在最后 —— 它就是 `HostRuntime` 剩下的那部分，改名为 `HostComposition`；
6. 再拆模块：`host-app`（入口 Activity / 引导 / 启动动画）与 `host-runtime`（上面四个），
   `host-core` 并入 `host-runtime`。

## 后果

- 拆分期间 `:host` 模块不拆，避免同时动"类边界"和"构建边界"两件事。
- `HostRuntime` 这个类名会逐步消失：先成为 Composition，再由 `host-app` 调用。
- 白名单里的 `HostRuntime.kt` 每完成一步就更新行数，最终从白名单删除。
