# ADR-0012：删掉 `AgentSurface`，声明只留 `PluginManifest`

- 状态：已采纳
- 日期：2026-11-10
- 相关：[ADR-0002 插件接口](0002-plugin-interface.md)、[ADR-0011 能力面归档与版本门](0011-host-capability-catalog-and-version-gate.md)

## 背景

`host-api` 里有两份"插件说明自己是什么"的声明：

```kotlin
// 旧
interface AgentSurface {
    val title: String
    val body: String
}

// 新的
data class PluginManifest(
    val id: String, val name: String, val version: String, val description: String,
    val capabilities: List<PluginCapability>, val settings: List<PluginSetting>,
    val permissions: List<String>,
)
```

`AgentSurface.title` 与 `PluginManifest.name`、`AgentSurface.body` 与 `PluginManifest.description`
是同一件事的两种写法。宿主侧只有三个消费者，全部只为了拿一个显示名和一段简介：

| 位置 | 用途 |
|---|---|
| `HostRouting.titleFor` | 页面标题兜底 |
| `HostInventory.cards` | 插件卡片的 title / body |
| `HostPluginManager` | 间接用卡片上的 title |

`AgentSurface.apiVersion` 更是死代码：版本判定走的是容器元数据（`PluginInstaller` 读出来的
`apiVersion` / `hostApi`），从来不读这个接口默认值。

## 决策

**删除 `AgentSurface`。插件对宿主"说明自己是什么"的唯一入口是 `ManifestProvider`。**

具体：

1. `host-api`：删掉接口，保留两个版本常量并搬进 `PluginContract.kt`
   （`AgentSurface.kt` 这个文件名已经没有对应的类型了，留着会误导）。
2. `HostRouting.titleFor` / `HostInventory.cards`：改读 `(instance as? ManifestProvider)?.manifest`，
   **并且校验 `manifest.id == 容器 id`** 才采信——与 `HostActivation.kt:274` 已有的规则一致，
   否则一个插件能替另一个插件改名、改简介。
3. 16 个插件：去掉 `AgentSurface` 与 `title`/`body` 两个属性。
4. `agent-phone` 之前压根没实现 `ManifestProvider`（它只有 `AgentSurface`），
   这次补上——否则删完它就没有任何自述了。

## 为什么是删，而不是给 `AgentSurface` 加字段

备选方案是"把 `AgentSurface` 做成结构化能力面"，即给它加 `capabilities`、`permissions`、`page` 等字段。
否决理由：

- **立刻就有了第二份 `capabilities`。** `PluginManifest` 里的能力清单是宿主已经在消费的
  （插件详情页由它渲染），再加一份就必须规定"哪份为准"，而这个规定本身就是新的出错点。
- **`PluginManifest` 已经在做 id 校验。** `AgentSurface` 没有 id，天生无法回答"这份声明属于谁"。
  加字段也补不上这一点，只能加个 `id`，那就等于把 `PluginManifest` 抄一遍。
- **收敛比扩展便宜。** 两份声明要维护两套文档、两套测试、两种写法；一份声明只有一个答案。

## 代价与限制

- **破坏性变更**：任何实现了 `AgentSurface` 的容器都编译不过。当前容器的处置是"实验版，
  可以当成不存在"（用户 2026-11-10 决定），所以不设兼容层，直接删。
- **`api/host-api.txt` 的 ABI 变了**：`AgentSurface`、`AgentSurface$DefaultImpls` 消失，
  常量持有类从 `AgentSurfaceKt` 变成 `PluginContractKt`。快照已刷新。
- **`manifest` 只在有活实例时拿得到。** 插件加载失败时卡片回落成显示 id——与改动前行为一致
  （那时也拿不到 surface），但要在文档里说清楚，免得被当成新 bug。
- **历史 ADR-0002 里仍写着 `AgentSurface`。** ADR 是当时的决策记录，不回改；
  以本条为准。
