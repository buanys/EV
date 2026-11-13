# DeepSeek Harness 精读

- 日期：2026-11-10
- 对象：`github.com/deepseek-ai/deepseek-harness`（浅克隆于 `/workspace/deepseek-harness`，249 MB，20665 文件）
- 为什么读：它与 EV 同源（都基于 Cordis），口号也是"一切皆插件"。EV 要从零重写全部插件，它的分法、事件模型、权限链是最近的参照物。
- 怎么读：四个方向并行精读代码与测试，再由本人复核关键结论（复核项见 §7）

---

## 0. 一句话结论

dsh 的价值不在功能清单，在**三条把"插件化"落到可执行层面的机制**：

1. **会话事件溯源**——日志是真相源，模型看到的历史是它的纯函数投影
2. **工具执行管线**——一次调用被切成九段，每段都有明确的拦截语义
3. **审批 seam**——四值词汇 + fail-closed + 可替换的 answerer

而它的**目录不是分层**。这一点影响最大，见 §1。

---

## 1. 它的分层真相：目录是"名词所有权"，不是技术层

`packages/` 下有 **53 个分组、230+ 个 workspace 成员**。乍看像一个层级树，实际上是按**能力名词**分目录。

### 三条横切的轴，全都不体现在目录上

| 轴 | 内容 | 落在哪 |
|---|---|---|
| **seam 角色** | Definition / Provider / Consumer / Policy / Bundle | **包名前缀**，不是目录 |
| **可达性** | default 挂载 / optional / experimental / SDK | `scripts/product-package-policy.ts` 逐包声明 |
| **进程半侧** | Host / 浏览器 / 出进程 | `host/` `client/` `api/` `sdk/` `acp/` 大致兜住 |

包名前缀的词表很稳定，读名字就知道它在扩展点的哪一侧：

```
fs              Service Definition
fs-local        Provider（按后端命名）
tool-bash       模型工具（约 28 个，散在 20 多个组里）
command-goal    用户斜杠命令
ui-theme        浏览器 UI
sandbox-policy  挂在 seam 事件上的策略
```

两条硬结论：

- **`experimental/` 不是一层，是状态桶**。61 个包里混着 `tool-terminal`（terminal 域）、`ptc-runtime-python`（ptc-runtime 域）、`client-ui-*`（client 域）。把状态当层是它自己的债。
- **权限/凭证不是分组的轴**。同一个组里可以既有纯数据又有要授权的：`credentials-local` 是本地 YAML，`deepseek-account` 是 OAuth；`ui-theme` 是像素，`connection` 是已认证传输。

它的 `architecture.zh.md` 开篇就否掉了"特权内核"："不存在需要打补丁的特权内核"。真正被强制的顺序只有一条：profile 的 patch 叠放。**所谓"层"，是加载顺序与依赖方向，不是目录嵌套。**

### 为什么这对 EV 重要

EV 现在按 `core / base / feature / integration / resource` 分层，dsh 按名词分目录。**两者不冲突，因为解的不是同一个问题**：

- dsh 跑在桌面上，权限是可组合的策略（sandbox 模式），所以它不需要"能力层"这个概念；
- EV 跑在 Android 上，**运行时权限是整包级的**——插件和宿主同 UID，`READ_SMS` 一旦授予是给整个 App 的。所以必须有一层显式声明 `HostPermission` 的"能力插件"来守门，否则用户没有地方说"这个插件不许看短信"。

**结论：EV 的分层要保留，但别把它当成 dsh 那样的"架构真理"。** 它是 Android 权限模型逼出来的，不是通用美学。这一点写进 README 的分层表里，免得以后有人问"为什么不学 dsh 按名词分"。

---

## 2. `core/agent-loop`：三相位状态机

### 结构

一轮对话不在 `agent` 包里，而在 `agent-loop` 的一个具体类 `ReactLoopAgent`（`packages/core/agent-loop/src/agent.ts:110`）。`agent` 包只提供契约与事件声明。

状态机只有三个相位（`agent.ts:53`）：

```ts
type Phase =
  | { kind: 'idle'; lastTurn: number }
  | { kind: 'maintenance'; abort: AbortController; … }
  | { kind: 'running'; abort: AbortController; turn: number; step: number; … }
```

驱动是 `kick()`（`agent.ts:268`）= `while (await this.turn()) {}`；`turn()` 返回"还有排队的 turn 吗"。

一轮内的阶段：

| # | 阶段 | 落点 |
|---|---|---|
| 1 | 开 turn | `session.append('turn/start')` |
| 2 | 领用输入 + 组装 + pre-step 判定 | `preStep()`（`agent.ts:283`） |
| 3 | 开 step | `session.append('step/start')` |
| 4 | 模型请求 | `step()`（`agent.ts:435`）→ `prepareRequest()` |
| 5 | 工具执行 | `ctx.tools.execute()`（整批交给执行器） |
| 6 | 收尾 | `agent/turn-stopping` → `step/end` → `turn/end` |

### 拦截点：三种语义，别混

| 事件 | 模式 | 能做什么 |
|---|---|---|
| `agent/pre-step` | waterfall | 返回 `{kind:'reject'}` 或 `{kind:'enter', messages}`；**能整批替换进 step 的消息** |
| `agent/request` | waterfall（已废弃） | 只能换 LlmCallConfig 的 controls；改 provider/model 直接抛错 |
| `agent/request-error` | waterfall | 返回 `{kind:'retry'}` 才重试 |
| `agent/turn-stopping` | serial | **没有返回值**，唯一手段是副作用 `agent.steer()` |

`turn-stopping` 那句注释值得抄进 EV：

> *Data decides, so listener order cannot change the outcome.*

——用数据（inbox 里有没有新东西）决定要不要继续，而不是靠监听器返回什么。这样监听器注册顺序不影响结果。

### 三个反直觉的地方（我们不该照抄）

1. **拒绝是 turn 级联，不是 step 级**。任一步 `pre-step` 拒绝，整个 turn 结束。
2. **被 claim 的消息在拒绝时人间蒸发**：日志里只有 `turn/start` + `turn/end{blocked}`，没有 `user/message`，没有 discard 事件。原文注释直说 *"it is neither discarded nor re-emitted"*。
3. **`kick()` 把异常吞了**（`agent.ts:268` 的 `catch {}`），失败只通过事件暴露；调用方 `whenIdle()` 不会被 reject。

对 EV：第 2 条是坑。用户说了话、被策略拒了，日志里连"他说过什么"都没有——审计与重放都会缺一块。**EV 应当保留被拒的输入和拒绝理由。**

### 工具执行：不是 for 循环

真正的调度在 `packages/core/tools/src/batch.ts`：

- 按 `executionMode` 分组：`parallel` 的连续段用**有界滚动池**，`exclusive` 单独成屏障
- 并行上限默认 **10**（`agent-loop/src/constants.ts`，`DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10`）
- **执行可重叠，日志与上下文严格按模型顺序**提交（`BatchSettlement.commit` 按模型顺序写 `tool/result`）
- 取消：停止补充、等在飞的落地、对未启动的 call **合成 `ABORTED_BEFORE_DISPATCH` 错误结果**
- 单个工具抛错**不炸整批**：降级成 `isError` 结果，兄弟调用继续；整批 reject 只发生在调度器自身故障

### 预算：明写的"没有"

`agent-loop/README.md` 的 Known Limitations 第一条：

> **No built-in turn budget** — a policy that bounds runaway turns must cancel from an existing lifecycle extension point such as `agent/turn-stopping`.

也就是说"跑飞了怎么办"不是内核的事，是插件在 `turn-stopping` 里 `cancel()`。EV 的 `RuntimeBudgetService` 现在在宿主里，位置更合理，但要确认它真能打断，而不是只记账。

---

## 3. `core/session`：日志即真相源，但**它自己不落盘**

这是全篇最值得注意的一条。

### 分工

- `core/session`：**内存里的 append-only 事件数组** + **把事件折算成模型可见消息的投影器**
- `session-persistence-jsonl`：另一个插件，监听 `session/event` 做 write-behind 落盘

原文（`src/index.ts` 模块头）：

> Event-sourced session service: append-only session log, in-memory store, and the derived LLM message history. **Persistence is a plugin concern (subscribe to `session/event`, drain on `session/flush`).**

所以"日志即真相"被两处强制：**类型层**（只有 5 类事件能进模型面）和**写入层**（append 先校验、失败则日志不变）。磁盘一致性是持久化插件的契约。

### 事件词表：62 种，但只有 5 种进模型

```ts
// packages/core/session/src/types.ts:447
export type SurfaceEventType =
  | 'system/message' | 'developer/message' | 'user/message'
  | 'assistant/message' | 'tool/result'
```

其余是 trace / replay 数据：`turn/start|end`、`step/start|end`、`assistant/attempt`、`tool/call`、`request/header`、`request/context`、`session/end-seed`。

三个设计点值得抄：

1. **系统提示词是 surface node 0**，不是 header 字段。`EpochHeader.system` 被写成 `never` 并在校验里明确拒绝。这是"一切模型可见 ⇔ 已记录"的直接后果——连提示词都能从日志重建。
2. **`ignorable` 默认"必需"**：读到不认识的事件类型，不带 `ignorable: true` 就**拒绝重建整个会话**。注释原话：*"defaulting to required means a forgotten marker over-refuses (an inconvenience) rather than silently resuming a gutted session."*（宁可拒，不可静默残缺）
3. **`assistant/attempt` 与 `assistant/message` 分开**：失败的尝试留证据但不进历史。

### 落盘：单写者 + 每批 fsync + 失败回滚

- 格式：JSONL，一事件一行，默认 zstd；
- 布局：`<root>/--<project-key>--/<session>/session.v4.jsonl.zstd`，每代一个文件，**不按大小切**；
- 每批 fsync；写失败**回滚到原长度**；单写者 `flock`，第二个写者直接 `SessionAlreadyOwnedError`，没有合并策略；
- 崩溃只丢"未 flush 的尾部"和"未结算的 attempt"。

### resume / fork / replay 共用三样东西

连续 `seq`、`inheritedEventCount`（精确切点）、`session/end-seed` 标记。三者对应三种"从哪个前缀开始"。

### 压缩不产生第二份真相

`compaction/*` 四个事件全是 **log-only**（无 `surfaceOp`）；摘要生效是一次 `user/message` + `surfaceOp: replace`，被压掉的原始事件**仍在日志里**，只是从投影里消失。

### 补洞：追加合成事件，不改写历史

崩溃后：请求了但没 `tool/call` → 补 `TOOL_NOT_STARTED`；有 `tool/call` 无 `tool/result` → 补 `TOOL_OUTCOME_UNKNOWN`。文案都强调"只重试只读/幂等，否则先核实外部状态"。

**这条对 EV 直接可用**：Android 上进程被杀是常态，工具执行到一半失联必须有"结果未知"这个明确状态，不能当成"没发生"。

---

## 4. 权限：目录叫 `guard`，但它**不是**权限

这是子智能体主动纠错的一处，且复核成立。

### `packages/guard/` 里只有两个包

```
repeat-tool-reminder   检测模型重复同一调用，注入提醒
timeout-policy         给声明了 timeoutMs 的工具套一个超时包装
```

两个都不做"允许/拒绝"。README 副标题：*"loop-hygiene guard family"*。**它守的是循环卫生，不是安全。**

### 真正的三层

| 层 | 机制 | 位置 |
|---|---|---|
| ① 执行前门 | `tools/pre-execute` waterfall，四值 `allow / deny / cancel / ask` | `core/tools/src/pipeline.ts:200` |
| ② 人工审批 | `ctx.approval.request()`，四值 outcome，**只有 `allowed-once` 放行** | `interaction/user-approval` |
| ③ 真围栏 | 文件系统 fence + 进程沙箱（bwrap/Landlock/Seatbelt） | `fs/fs-sandbox`、`sandbox/*` |

判定顺序（`pipeline.ts` 的 `prepare → dispatch → complete`）：

```
可见性 → executor.reject → pre-execute waterfall → ask 审批 → 单调守卫
→ tools/execute 包装（超时/重试）→ 工具体 → post-execute
```

两个顺序事实：

- **守卫在审批之后跑**：用户会先被弹窗问一次，然后守卫才可能拒绝。
- **策略看到的是未按 schema 校验的原始参数**：`pre-execute` / 审批 / 守卫全都在校验之前。

### 失败统一降级成文本

任何拒绝、抛错、超时，都变成：

```
Error: <message>
```

结果文本回给模型，`isError: true`，**永不终止 turn**。机器的错误详情只进投影给 UI，不进模型内容。

### 审批：阻塞等待，不是挂起恢复

- 形态：`await approval.request(...)` 卡在那一次工具调用里；
- outcome 四值：`allowed-once | rejected | cancelled | unavailable`，**只有第一个放行**；
- 服务自己从不弹窗，只 dispatch `approval/request` waterfall，answerer 是普通 listener；
- **没有 answerer 时默认 `unavailable` → deny**；
- 审计在会话日志里是一对事件：`approval/asked` + `approval/decided`，且**必须在 open turn 内**，否则直接抛错（"a bare event between turns is crash-tail garbage on reload"）；
- **pending 的问句不落盘**：崩溃后不重新问。

### fail-closed 的五条路径

| 情况 | 结果 |
|---|---|
| `ctx.get('approval')` 为 undefined | deny |
| 没有 agent 可路由 | deny |
| answerer 返回 `unavailable` | deny |
| answerer 抛错 | deny（"fail the QUESTION closed, not the caller's tool call open"） |
| answerer 返回非法值 | 归一成 `unavailable` → deny |

### 三处 fail-open，必须知道

1. **`tools/pre-execute` 默认 `allow`**：没 listener 就全放行。这不是 bug（否则不装插件的部署没法用工具），但意味着"没有策略"和"策略放行"在运行时不可区分。
2. **`dsh-base` 不挂任何 answerer**：裸 base 跑 `ask` 必然 `unavailable` → deny。所以"要人命"的操作在默认安装里是**被拒**，不是被问。
3. **网络没有边界**：`SandboxMode` 只管文件效果，网络与进程策略不在词表里。

### 没有"危险等级"

`ToolDefinition` 的全部字段里没有 `risk` / `danger` / `requiresApproval`。**工具无法自我声明危险。** 唯一的分级藏在默认关闭的实验包 `experimental/auto-review` 里，是拿模型当判官（`risk: low|medium|high`），而且是 runtime 判断，不是声明。

### 没有"记住本次会话允许"

`user-approval` 的 Known Limitations 第一条：

> Only one-shot grants exist — the outcome vocabulary has `allowed-once` but no `allow-always`, remembered rule, revocation, or grant store.

---

## 5. 对 EV 的结论：照抄什么、不照抄什么

### 该抄（按价值排序）

| # | 抄什么 | 为什么 |
|---|---|---|
| 1 | **事件类型分层**：全量事件 vs 模型可见事件两个词表 | EV 现在 `SessionEvent` 只有 `Message` / `TurnEnded` 两种，太粗——重放时看不出"这一轮为什么停"、"工具调了没回来" |
| 2 | **单写者 + 每批 fsync + 写失败回滚长度** | EV 的 `EventLog` 现在是 append 到文件，崩溃时尾部半条的处理没说清 |
| 3 | **补洞用"追加合成结果"**：`TOOL_OUTCOME_UNKNOWN` / `TOOL_NOT_STARTED` | 手机进程随时被杀，这个是刚需 |
| 4 | **审批要留审计对**：asked + decided 两个事件 | EV 现在的审批是纯内存的，日志里查不到"谁批过什么" |
| 5 | **审批 outcome 四值 + 五条 fail-closed 路径** | EV 只有"没有 gate 就拒"，缺"answerer 抛错"和"回答非法" |
| 6 | **`turn-stopping` 用数据决定而非返回值** | 监听器注册顺序不影响结果，这条设计纪律值得写进 EV 的约定 |

### 不该抄

| 项 | 原因 |
|---|---|
| **拒绝时把用户输入丢掉** | 审计与重放会缺一块。EV 要保留输入 + 拒绝理由 |
| **把危险判断交给 LLM（auto-review）** | EV 的 `ToolSpec.dangerous` 是静态声明，可预测、可审计。11 个插件 26 个 dangerous 工具已经是声明式的了，别退回去 |
| **`experimental/` 状态桶** | 61 个包横跨所有域，读目录读不出它是什么。EV 的 `experimental-` 归档已经做对了（物理移出） |
| **无 turn 预算** | dsh 明写"没有内置预算，靠插件在 turn-stopping 里 cancel"。EV 有 `RuntimeBudgetService`，应当真的能打断 |
| **目录即分层** | 见 §1：EV 的分层是 Android 权限模型逼出来的，不是通用美学，但也不需要向 dsh 看齐 |

### EV 已经比它更对的地方

- **`ToolSpec.dangerous` 声明式危险标记**：dsh 没有，只能靠 LLM 判官。
- **`HostPermission` 声明 → 用户授权 → 服务下发**的三段式：dsh 的权限是可组合策略（沙箱模式），没有"用户逐项授权某个插件"这个概念。Android 上这条是必须的。
- **物理归档过期插件**：dsh 的 experimental 桶会长期腐化。

### 需要重新审视的一处

EV 的 `ApprovalDecision` 是：

```kotlin
ALLOW_ONCE / ALLOW_SESSION / DENY
```

`ALLOW_SESSION` 在 dsh 里**明确不存在**（它是"only one-shot grants"）。我们保留它的理由很实在：Android 上每步都弹窗会逼用户无脑点允许。但它的语义实际是"这个工具之后所有调用都不再问"。

**建议**：把 `ALLOW_SESSION` 的作用域从"gate 生命周期"收紧到"本 turn 内"（改名 `ALLOW_TURN`），或者至少记录"哪一轮批准了什么"，让审计能回答"它是在我批准之后才动的吗"。

---

## 6. 与 EV 现状的对照

| 维度 | dsh | EV 现在 | 结论 |
|---|---|---|---|
| 插件契约 | TS 接口 + Cordis `ctx` | Kotlin `Plugin<Unit>` + `ManifestProvider` | 各有所长，不动 |
| 插件分类 | 名词分组 + 包名前缀角色 | core/base/feature/integration/resource | EV 保留，理由见 §1 |
| 会话 | 62 事件 / 5 进模型 | 2 事件（Message/TurnEnded） | 要扩 |
| 落盘 | 独立插件 + 单写者 + fsync | 插件内自写 jsonl | 要补 fsync 与回滚 |
| 工具并发 | 分组 + 滚动池 + 结果按模型序 | 一轮内顺序执行（未核实） | 要补上限与并行 |
| 危险标记 | 无 | `ToolSpec.dangerous` | EV 保留 |
| 审批 | 四值 + fail-closed + 审计 | 三值，无审计 | 要补 |
| 预算 | 无（靠插件） | `RuntimeBudgetService` | EV 更好，但要能打断 |

---

## 7. 读法与边界

**读了什么**（由子智能体全量读 + 本人抽样复核）：

- `core/agent-loop/src/{agent,inbox,constants}.ts` 全文 + README + 测试 `interception.spec.ts` / `request-error.spec.ts` / `tool-order.spec.ts`
- `core/session/src/*` 全部 10 个文件（含 30 KB 的 `surface.ts`）+ 多个测试 + `session-persistence-jsonl`
- `core/tools/src/{pipeline,contracts,batch}.ts` 关键段 + `interaction/user-approval` + `guard/*`
- `packages/README.zh.md`、`architecture.zh.md`、`product-package-policy.ts`、20+ 分组 README

**本人复核过的点**（防止转述失真）：

- `Phase` 三相位定义（`agent.ts:53`）
- `DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10`
- `packages/guard/` 实际只有 `repeat-tool-reminder` 与 `timeout-policy`
- `PreToolDecision` 四值（`contracts.ts:357`）
- `SurfaceEventType` 五值（`types.ts:447`）
- `tools/pre-execute` 的默认值是 `{kind:'allow'}`（`pipeline.ts:200`）
- `ApprovalOutcome` 四值（`user-approval/src/types.ts:32`）

**没读的**：`client/`（约 70 包）、`experimental/`（61 包）、`native/`、`python/`、大部分测试。行号来自 grep 定位，个别成员只到符号级。

**克隆方式**：`git clone --depth 1`，只有一次提交，看不到历史与演进理由。要看"为什么这么改"得拉全量。
