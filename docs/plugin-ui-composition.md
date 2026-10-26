# 插件 UI 模型（D13）

插件有两条渲染路径，**不是二选一，而是按状态需求选**。

## 两种契约

| 契约 | 位置 | 渲染在哪 | 适合 |
|---|---|---|---|
| `AgentUi.Content(modifier)` | `plugin-api/.../AgentUi.kt` | 宿主列表内联 + 独立页面兜底 | 无状态展示、设置表单 |
| `AgentPage.Page(modifier, onBack)` | `plugin-api/.../AgentPage.kt` | 只以独立 Activity 全屏渲染 | 有状态页面（输入框、滚动位置、会话） |

## 为什么会变成"两棵 composition"

宿主列表会给每个 `AgentUi` 组一次内联 composition；点「打开页面」时 `PluginPageActivity` 再组一次。
两次都调用 `modules.peek(...)` 拿**同一个插件实例**，但 composition 是两棵独立的树，`remember` 出来的状态互不相干。

对设置页无所谓：每次重组都从 `AppearanceService` 读当前值。
对聊天页是灾难：草稿文字、滚动位置会出现两套，切换页面时"消息没了"或"输入框清空"。

所以聊天页（`plugins/ev-ui`）实现 `AgentPage` 而非 `AgentUi`，宿主只给它页面这一条路径。

## 规则

1. **状态放 Cordis 服务，UI 无状态。** 需要跨重组或跨页面存活的数据，放宿主提供的服务
   （如 `AppearanceService`）或插件自己注册的服务；`@Composable` 里只做展示与转发。
2. **有状态页面用 `AgentPage`。** 一旦引入 `remember { mutableStateListOf(...) }` 这类会话状态，
   就必须确保它只有一棵 composition。
3. **内联内容要幂等。** `AgentUi.Content` 可能被组多次，不能有副作用（不要在这里写文件、发请求）。
4. **主题不自带。** 颜色、字体、圆角都从 `EvTheme`/`MiuixTheme` 读，插件不定义调色板。

## 进程被杀后重进插件页（D11 说明）

`PluginPageActivity` 可能被系统在**新进程**里恢复，此时插件从未加载。恢复路径：

1. `PluginPageActivity.onCreate` 取 `(application as EvApplication).startedRuntime`；
2. 为 `null`（本进程没跑过 sync）→ `startActivity(MainActivity, CLEAR_TOP|NEW_TASK)` 并 `finish()`；
3. 主界面执行完整 sync（扫描、校验、批准检查、加载），用户可再次打开插件页。

运行时归 `EvApplication` 所有（[EvApplication.kt](../host/src/main/kotlin/ev/buan/EvApplication.kt)），
不再由 Activity 赋值；`hasStarted` 标记本进程是否完成过一次 sync。
