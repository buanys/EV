# 扩展点机制设计方案（声明式插件挂载）

- 状态：**草案，未实施**（2026-10-25）
- 相关：[architecture.md](architecture.md)、[ADR-0002](adr/0002-plugin-interface.md)、[plugin-ui-composition.md](plugin-ui-composition.md)

## 1. 背景与问题

agent-model 的设置页写死在 `ev-ui` 里（抽屉 → 设置 → 模型服务）。后果：

- 能力插件不拥有自己的 UI，能力做了但没有门面；
- 每加一个能力插件，ev-ui 都要改代码——插件系统的"可扩展"在 UI 层失效。

## 2. 目标与非目标

**目标**：插件可以向其他插件声明的插槽挂载 UI，被挂插件零改动。

**非目标**：

- 不动内核（`loader/core/...` 已冻结，见 [kernel-freeze.md](kernel-freeze.md)）；
- 不做 Eclipse 式完整扩展注册表（schema 仲裁、加载顺序引擎都是超标的坑）；
- v1 只解决"设置页挂载"一个问题。

## 3. 核心设计：广告 + 送货两层

Composable 是代码不是数据，纯声明做不到。分两层：

### 3.1 广告层（sidecar JSON）

插件在 sidecar 里声明贡献元数据，**不执行代码即可列出条目**：

```json
{
  "id": "ev.buan.plugins.agent.model",
  "version": "1",
  "entryClass": "ev.buan.plugins.agent.model.AgentModelPlugin",
  "apiVersion": 1,
  "contributes": [
    { "slot": "ev.settings.page", "title": "模型服务", "order": 50 }
  ]
}
```

广告层的价值：

- ev-ui 抽屉据 manifest 直接显示"模型服务"入口，点击后才触达插件代码；
- 安装期可校验：插槽不存在、元数据不全 → 拒载，错误前置。

### 3.2 送货层（ServiceDirectory）

插件 `apply` 时把真正的页面实现 publish 到 `ServiceDirectory`；ev-ui 点击条目时
`lookup` 获取并渲染。**复用现有注册机制，不另起注册表。**

```kotlin
// plugin-api：契约
interface SettingsContribution {
    val slotId: String          // 与 sidecar 中 slot 一致
    fun page(): AgentPage
}

// agent-model：apply 时发布
services.publish(SettingsContribution::class, ModelSettingsContribution(...))

// ev-ui：渲染抽屉时收集
val entries = contributions.lookupAll(SettingsContribution::class)
```

## 4. 职责划分

| 层 | 职责 |
|---|---|
| `plugin-api` | 插槽契约：插槽 id 常量、贡献接口；随 `PLUGIN_API_VERSION` 版本化 |
| `host-core` | sidecar `contributes` 字段解析与校验（插槽存在性、元数据完整性） |
| 宿主 `:host` | 把校验通过的贡献清单透传给插件上下文（不做仲裁业务） |
| 消费插件（ev-ui） | 枚举贡献、渲染条目、零贡献时降级为静态抽屉 |
| 贡献插件（agent-model 等） | sidecar 声明 + publish 实现 |

**内核零改动。**

## 5. v1 范围

- 插槽只开一个：`ev.settings.page`（设置页整页，`AgentPage` 形态）；
- 贡献类型只有一种：整页；
- 冲突策略：同 slot 同 title 取 order 最小者，其余记日志忽略——不做复杂仲裁；
- 声明了贡献但未 publish 实现：条目灰显，点击提示"插件未激活"，不崩。

## 6. 后续扩展（v1 跑通后再议）

- 工具面板插槽（ev-ui 抽屉的工具区）；
- 主页卡片插槽；
- `ev.settings.page` 之外的贡献类型（内联 `AgentUi`）。

## 7. 纪律（防止新一轮"越来越乱"）

1. 插槽总数保持少而明确，新插槽需要写进本文档并说明"为什么现有插槽装不下"；
2. 消费方必须容忍零贡献与贡献插件缺席；
3. 插件只允许挂载到**已声明的插槽**，不允许探入其他插件内部结构。
