# ADR-0008：未签名插件的信任从"字节"改为"能力面"

- 状态：**已采纳**（阶段 1、2 落地；阶段 3、4 待做）
- 日期：2026-11-05
- 关联：ADR-0003（权限不是沙箱）、ADR-0007（审批分层）

## 背景

`UnsignedApprovalStore` 记的是 `id -> sha256`，注释写得很清楚：

> Approval is bound to the payload, so re-signing is not what we trust - these bytes are.

这个选择对"防篡改"是对的，但后果是：**插件改一个字节，就要人重新点一次确认**。
本机实测过两次——换掉 agent-api / agent-log / agent-brain 三个 jar 后它们停在 `pending`，
12 个依赖它们的插件全部 `DEPENDENCY_MISSING`，界面直接起不来。

对"AI 自己写插件"来说这不是摩擦，是死锁：agent 每迭代一次自己的插件，都要人打断一次。

## 决策

把信任从「字节」升级为「能力面指纹」。**判定由 `CapabilityTrust.decide` 完成，
它是纯函数，不碰存储、不碰时间。**

```kotlin
data class CapabilitySurface(
    val permissions: Set<String>,   // sidecar 的 permissions
    val dependencies: Set<String>,  // sidecar 的 dependencies
)
```

判定顺序（插在现有 `UNSIGNED_NOT_APPROVED` 之前）：

| 情况 | 结果 |
|---|---|
| 有签名 | 走原有签名路径，本模型不涉及 |
| 没有记录 | `pending`，与今天一致 |
| 有记录，opt-in 关闭 | `pending`（默认状态） |
| 有记录，opt-in 打开，能力面相等 | **自动通过** |
| 有记录，opt-in 打开，能力面有增删 | `pending`，日志说明原因 |

### 为什么依赖也算扩权

ADR-0003 已经写明权限不是沙箱。插件只要把高权限插件加进 `dependencies`，
就能 `lookup` 它发布的服务，间接用上它的能力。所以「新增依赖」与「新增权限」等价。

### 修正：指纹里没有 capabilities

初版提案把 `PluginManifest.capabilities` 也算进指纹。实现时发现 **sidecar 里没有这个字段**
（只有 id / version / entryClass / apiVersion / dependencies / permissions），要拿到它得解析
dex 里的 manifest。这个代价现阶段不值——权限与依赖已经覆盖了扩权的两条实际路径。

### 放弃与保住

- **放弃**：不再保证"同一 id 的字节一定是你见过的那批"。恶意更新可以在不改声明的情况下推送。
- **保住**：扩权（新权限 / 新依赖）必须人批；新 id 必须人批；撤回、卸载、安全模式路径不变。

## 三个决策（2026-11-05 由用户拍板）

1. **默认关。** 没有 opt-in 就仍然问人。字段是 `CapabilityTrustStore.trustsUpdates(id)`，默认 false。
2. **撤回整条链。** `forget(id)` 清掉能力面与 opt-in，调用方同时清 `UnsignedApprovalStore` 里的字节批准。
3. **v1 不做「退回上一版」。** 只记录时间线，不回滚。退回需要保留旧 jar 与 private copy 的映射，是另一个工程。

## 实现位置

| 文件 | 职责 |
|---|---|
| `host-core/.../trust/CapabilityTrust.kt` | 能力面、判定、给日志用的一句话。纯 JVM |
| `host-core/.../trust/CapabilityTrustTest.kt` | 八条路径，含"依赖顺序不影响相等" |
| `host/.../CapabilityTrustStore.kt` | 存能力面与 opt-in。**独立 SharedPreferences 文件** `ev.trust` |
| `host/.../PluginInstaller.kt` | 在待批准分支里调用判定；通过时记录能力面 |

为什么要独立一个 SharedPreferences：`UnsignedApprovalStore.approved()` 用 `preferences.all`
当作 `id -> sha256` 表读，往同一个文件加 `perms:xxx` 会被当成一个插件 id，把信任表读脏。

## 还差什么（阶段 3、4）

- **UI 区分「能力面变化」与「仅代码更新」**，并展示差异。没有它，opt-in 等于让人盲签——
  所以在 UI 做出来之前，**不要打开这个开关**。
- 插件页的「信任后续版本」开关与「撤回」入口。

## 不做什么

- **不做作者签名体系**。真正回答"作者是谁"需要插件分发与密钥管理，EV 目前连第三方分发都没有。
  用插件 id 当作者标识是这一阶段的现实上限，不该假装它是身份认证。
- **不把未签名插件升级为可信**。本提案只改变"什么时候需要人确认"，不改变"未签名 ≠ 已验签"。
