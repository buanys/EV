# ADR-0008：未签名插件的信任从"字节"改为"能力面"（提议，未采纳）

- 状态：**提议**，等待产品决策
- 日期：2026-11-05
- 关联：ADR-0003（权限不是沙箱）、ADR-0007（审批分层）

## 背景

`UnsignedApprovalStore` 记的是 `id -> sha256`，并且代码注释说得很清楚：

> Approval is bound to the payload, so re-signing is not what we trust - these bytes are.

这个选择对"防篡改"是对的，但它有一个直接后果：**插件改一个字节，就要人重新点一次确认**。
在本机实测过两次：换掉 agent-api / agent-log / agent-brain 三个 jar 之后，它们停在
`pending`，进而 12 个依赖它们的插件全部 `DEPENDENCY_MISSING`，界面直接起不来。

对"AI 自己写插件"来说，这不是摩擦，是**死锁**：agent 每迭代一次自己的插件，都要人打断一次。
所谓自举永远停在"它写、你批"。

## 提案

把信任记录从「字节」升级为「能力面指纹」：

```kotlin
data class ApprovalRecord(
    val pluginId: String,
    /** sidecar 里声明的宿主权限。 */
    val permissions: Set<String>,
    /** sidecar 里声明的依赖 id —— 它也等于扩权，见下文。 */
    val dependencies: Set<String>,
    /** PluginManifest.capabilities 的 id 集合。 */
    val capabilities: Set<String>,
    val approvedAt: Long,
    /** 仅供审计与回滚提示，不参与判定。 */
    val lastApprovedSha256: String,
)
```

判定顺序（插在现有的 `UNSIGNED_NOT_APPROVED` 之前）：

1. 有签名 → 走原有签名路径，本提案不涉及
2. 没有记录 → `pending`，与今天完全一致
3. 有记录，且 `permissions` / `dependencies` / `capabilities` 三个集合**完全相等**
   → 自动通过，更新 `lastApprovedSha256`
4. 有记录，但任一集合有增删 → `pending`，并且**只说差异**：
   "新增权限 PLUGIN_STORAGE、新增依赖 ev.buan.plugins.agent.api"

### 为什么依赖也算扩权

ADR-0003 已经写明权限不是沙箱。插件 A 只要把高权限插件 B 加进 `dependencies`，
就能 `lookup` B 发布的服务，间接用上 B 的能力。所以「新增依赖」与「新增权限」等价，
不能只在权限集合上做指纹。

### 放弃的保证

- 不再保证「同一 id 的字节一定是你见过的那批」。恶意更新可以在不改能力声明的情况下推送。

### 保住的保证

- 扩权（新权限 / 新依赖 / 新 capability）必须人批
- 新 id 必须人批
- 撤回、卸载、安全模式路径都不变

## 三个必须由人拍板的问题

1. **默认开还是关？**
   建议：默认**关**。插件页给一个 per-plugin 的「信任该作者的后续版本」开关（opt-in）。
   默认关意味着这次改动对现有行为零影响，用户可以逐个放开。

2. **撤回的语义是什么？**
   撤回「这一个版本」还是「这个 id 的整条授权链」？
   建议后者：用户点撤回时想表达的通常是"别再让它更新了"。

3. **回滚要做到什么程度？**
   v1 建议只记录时间线（谁在什么时候批准了哪次能力面变更），不做「退回上一版」。
   退回需要保留旧 jar 与旧 private copy 的映射，是另一个工程。

## 分阶段

| 阶段 | 内容 | 风险 |
|---|---|---|
| 1 | `ApprovalRecord` 数据模型 + 旧记录迁移（旧记录视为"只有 sha256"，首次仍要问） | 低 |
| 2 | 判定逻辑 + 单测（相等 / 扩权 / 缩权 / 无记录四条路径） | 中 |
| 3 | UI 区分「能力面变化」与「仅代码更新」，并展示差异 | 中 |
| 4 | 信任页开关 + 撤回语义 | 中 |

阶段 1 和 2 可以在不改变默认行为的前提下先落地（开关默认关），阶段 3 之前不要打开开关——
用户看不到自己批了什么的时候，opt-in 等于让人盲签。

## 不做什么

- **不做作者签名体系**。真正解决「作者是谁」需要插件分发与密钥管理，EV 目前连第三方分发都没有。
  用 id 当作者标识是这一阶段的现实上限，不该假装它是身份认证。
- **不把未签名插件升级为可信**。本提案只改变"什么时候需要人确认"，不改变"未签名 ≠ 已验签"。
