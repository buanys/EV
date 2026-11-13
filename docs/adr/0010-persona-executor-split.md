# ADR-0010：人格层与执行层分离（ev-bot）

- 状态：**草案，待决策**（2026-11-08）· ⚠️ 前提已被
  [2026-11-08 方向判断](../research/2026-11-08-ev-bot-direction.md) 部分推翻，**不要直接实施**
- 关联：ADR-0002（插件接口）、ADR-0007（审批策略与守卫）、ADR-0009（信任三档）

## 背景

### 现在是什么样

`host-api/.../AgentService.kt` 是唯一的对话大脑契约，注释写得很直白：

> The conversation brain, **published by an agent plugin** into `ServiceDirectory`.
> The host does not know this contract: consumers look the service up optionally,
> so a missing brain is a normal "not connected" state rather than a failed dependency.

实现者是 `plugins/feature/agent-brain`（13 文件 / 1703 行）：`AgentBrainService`（399）管会话，
`TurnRunner`（372）跑一轮，`ToolExecution`（209）调工具。界面是 `plugins/feature/ev-ui`——
会话流、轮块轨迹、审批弹窗全在里面。

人格目前的全部实现，是 `send()` 的一个参数：

```kotlin
systemPrompt: String? = null   // role/persona instruction for this turn … never persists
```

### 缺三样东西

1. **没有持久人格**。每轮现拼，谁调用谁决定，没有"这个人是谁"的单一来源。
2. **没有时机**。系统只在你开口时才动。"半小时后提醒我""这件事你惦记一下"做不到
   （见 `docs/coverage-tasks.md` 的时间维度断点）。
3. **人格与执行共用一条上下文**。长 persona 挤占指令预算，表现为工具调用参数变松、
   失败时倾向表演性道歉、输出变长。这不是"情感耗电"，是 token 预算被挤。

### 一个外部参照

DeepSeek Harness 团队 2026-10-31 的 Super-agent：Router / Worker 双层，Router 承接人类交互、
维护全局上下文并分派，Worker 在隔离会话并发执行、只向 Router 汇报。
**它的动机是并行**（人只能同时盯 3–5 个会话）；**本决策的动机是上下文纯度**。
形状相同，取舍不同——照搬会引入我们不需要的并发调度复杂度。

## 决策一：ev-bot 是插件，不是 host 的一层

因为 `AgentService` 本来就是插件发布的，宿主不认识这个契约。把它放进 host，
等于为了一个新插件去改宿主，与 ADR-0002 的分工方向相反。

推论：**ev-bot 没有自己的界面。** 首屏、会话流、输入框都在 `ev-ui`。
ev-bot 只发布服务，由 ev-ui 消费。所谓"大项目插件"，大在逻辑，不在 UI。

## 决策二：分两阶段，第一阶段不拆 brain

### 阶段一：PersonaService（可逆，不动 agent-brain）

新增一个**可选**服务，`ServiceKey("agent/persona")`：

```kotlin
interface PersonaService {
    val state: PersonaState                       // 在 / 专注 / 休息
    fun systemPromptFor(turn: PersonaTurn): String?
    suspend fun route(input: String): Route       // Answer / Dispatch / StaySilent
}
```

`ev-ui` 在把输入交给 `AgentService` 之前多问一句：

- 服务不存在 → **完全现状行为**（这是一条硬要求，见不变量 4）
- `Answer` → ev-bot 自己用模型答，不派活（闲聊、查记忆、简单信息）
- `Dispatch` → 带上 `systemPromptFor(turn)` 的结果调 `agent-brain`，执行上下文里只有
  这一轮的薄指令，没有持久人设
- `StaySilent` → 用于主动场景：它有话但选择不说

**为什么不抢 `agent/service`**：`ServiceKey` 是单例，`agent-brain` 已经占了。ev-bot 若也发布它，
两者冲突；若 ev-bot 想调它，调到的还是自己。所以阶段一必须绕开这个 key。

### 阶段二：Executor 拆分（不可逆，需另行复审）

- `agent/service` 归 ev-bot（对外的人格面）
- 新建 `AgentExecutor` 契约，把 `TurnRunner` + `ToolExecution` 从 agent-brain 移出
- `agent-brain` 退化为 Executor 的一个实现

这是真工程（1703 行里要动 581 行），阶段一跑稳之前不做。

## 决策三：三条不变量

1. **事实不经过人格层。** 成功 / 失败 / 部分完成由执行层**直接**写 UI。
   ev-bot 可以包语气（"我试了，进不去"），但不能改变状态本身。
   贴心的 AI 骗人，比冷淡的 AI 报错严重。
2. **审批不经过人格层。** `ApprovalService` 的通知直达用户。ev-bot 没有放行权、
   不能替用户决定、不能改写风险描述。
3. **人格层不持有工具。** 任何需要碰手机、碰文件的动作一律派出。
   边界：**碰设备或碰文件的派，只碰自己记忆的自己答。**

第 4 条是关于降级的，单列：

4. **零依赖降级。** ev-bot 缺席、未授权、加载失败时，`ev-ui` 必须退回当前行为，
   不报错、不灰屏、不提示"功能不可用"。

## 决策四：从 Super-agent 借三条，弃两条

| 借 | 理由 |
|---|---|
| 两级过滤（程序规则先筛 → 模型再判断） | 主动性要用。线上过滤比模型过滤便宜得多 |
| 异步 ReAct Loop（start 立即返回） | 手机刚需：长任务执行期间仍能对话 |
| 提前行动（speculative） | "静默复现并修复"翻译为手机：收到会议邀请先核日程、备草稿 |

| 弃 | 理由 |
|---|---|
| 存算分离 | 那是解决"电脑关机任务停"。手机本来就是本地，对等物是前台服务 + WorkManager，不是云端 |
| Inline & Spill / 链式召回 | 设计对，但我们的上下文体量没到。现在上只是多一层抽象负担 |

## 非目标

- 多 Worker 并发执行（阶段一、阶段二都不做）
- 云端执行、跨设备续跑
- 改动内核（`core` / `loader` / `hmr` / `include` / `packages`）
- 重写 `ev-ui`

## 后果

**正面**

- 人格可独立演进，改人设不碰工具链
- ev-bot 缺席时行为不变，装不装都能用
- 主动性有了明确的归属者（现在没有任何模块负责"什么时候开口"）

**负面**

- 两次推理：`Answer` 与 `Dispatch` 各一次。手机上的 token 与电量都记这笔账
- `ev-ui` 多一个可选依赖，降级路径必须严格
- 阶段二要拆一个 1703 行的插件

## 未决问题

1. **命名**：`ev-bot` / `agent-self` / `agent-persona`。现有 17 个插件全是 `agent-*`，
   且 `agent-brain` 已占"大脑"一职。
2. **`AgentExecutor` 的契约形状**：是否沿用 `AgentProgress` 事件流，还是更窄的
   `run(task, constraints) -> Outcome`。
3. **Trigger 来源与隐私边界**：通知、日历、位置各需什么权限，两级过滤的"程序规则"能看多深。
4. **轻问答用哪个模型**：同一 provider 还是更便宜的档位。
5. **第一刀的验收**：人格生效到什么程度算"跑通"——建议先只验三条：
   同一个问题在 `Answer` 与 `Dispatch` 下的输出形态不同、执行轨迹里不含人设、ev-bot 卸载后行为不变。
