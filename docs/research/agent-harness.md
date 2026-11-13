# Agent / Harness 调研笔记

写这份文档的原因：EV 的 `agent-brain` 循环只有 **3 轮**、串行调工具、没有预算和重复检测，
表现是"一句话它动两个工具就停了"。要改得有理有据，先把公开文献里已经验证过的结论摊开，
再逐条对到 EV 的现状上。

范围限定：只收**能落到 EV 代码里**的结论，不收泛泛的综述。

## 参考文献

| # | 文献 | 关键贡献 |
|---|---|---|
| R1 | [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/abs/2210.03629) (Yao et al., ICLR 2023) | 推理轨迹与动作交替生成；ALFWorld +34%、WebShop +10% 成功率；reasoning trace 带来可解释性 |
| R2 | [Reflexion: Language Agents with Verbal Reinforcement Learning](https://arxiv.org/abs/2303.11366) (Shinn et al., NeurIPS 2023) | 用自然语言复盘替代权重更新；HumanEval pass@1 91% 对 GPT-4 的 80% |
| R3 | [SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering](https://arxiv.org/abs/2405.15793) (Yang et al., NeurIPS 2024) | **接口设计本身**显著影响成功率；ACI 概念；强制绝对路径后模型零错误 |
| R4 | [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) (Anthropic, 2024) | workflow 与 agent 的架构分界；agent = "LLM 在循环里用工具"；工具文档要像给初级开发者写 docstring |
| R5 | [Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) (Anthropic, 2025) | context rot；compaction / structured note-taking / sub-agent 三种长任务手段 |
| R6 | [AgentBench: Evaluating LLMs as Agents](https://arxiv.org/abs/2308.03688) (Liu et al., ICLR 2024) | 8 个环境评测；失败主因是长程推理、决策与指令遵循，而非模型知识 |
| R7 | [Semantic Early-Stopping for Iterative LLM Agent Loops](https://arxiv.org/abs/2606.27009) (Shrivastava, 2026) | 固定 `max_iterations` 是**对改进视而不见的语法性 kill-switch**；无判官的语义停止器省 38% token 且质量持平 |
| R8 | [Better Harnesses, Smaller Models](https://arxiv.org/abs/2607.08938) (Yang et al., 2026) | 任务难度大部分**可从模型转移到 harness**；小模型配适配 harness 达 89.7% 大模型性能、4% 成本 |

---

## 附：EV 主循环现状全图

改造前必须先写清楚现在到底是什么。以下全部来自代码，不是推测。

**入口**：`AgentService.send(sessionId, text, model, systemPrompt)` —— 一次调用 = 一个用户轮。

**阶段 0 · 能力解析**（`AgentBrainService.kt:61-74`）
`capabilities.current()` 每次调用重新查，不缓存。模型插件缺失 / 模型列表为空 / 找不到指定模型，
三种情况都返回 `degraded` 文本回复，不抛异常。

**阶段 1 · 组装输入**（`:76-89`）
```
systemPrompt(本轮角色指令，不落库) + HistoryTrim(history, 20轮, 24000字符) + userMessage
```
`HistoryTrim` 按 **USER 边界成组**（`HistoryTrim.kt:39-51`），assistant 的 `tool_calls` 与对应
`TOOL` 结果**同进同出**（拆开会发出连不上对的非法对话）。裁剪只作用于**模型视图**，
`memory` 是完整 append-only 日志。用户消息在进循环前就落库（`:89`）。

**阶段 2 · 循环**（`:97-129`，`MAX_TOOL_ROUNDS = 3`）
```
modelService.complete(target, inputsForModel, specs)      // 一次性阻塞，非流式
  ├─ CancellationException → 透传
  └─ 其他异常 → failure(带上已消耗 usage)
events += reply.events；usages += reply.usage
calls = events 里 TOOL_CALL；为空 → break
toolCallOf 归一化（缺 id 补 call_N）
assistantTurn(带 toolCalls) → inputs + 落库       // 原生协议：调用消息在结果之前
execute()（:196-221）逐个串行：
   registry 空        → "工具系统未启用"
   工具不存在         → "没有名为 X 的工具"
   dangerous + 无审批 → fail-closed 拒绝
   DENY              → "用户拒绝了本次执行"
   审批抛异常         → 拒绝（绝不当成允许）
   invoke 抛异常      → "工具调用失败：…"
   结果 truncateUtf16Safe(8000)，不切代理对
每个结果 → TOOL 消息(role/name/toolCallId) → inputs + 落库
```

**阶段 3 · 收尾**（`:131-146`）
`last` 为空 → `degraded`；否则取最后一条 assistant 文本（空则兜底文案），落库，
返回 `copy(message, events, usage 聚合)`。

**由此确认的四个事实：**

1. **降级路径非常完整**：无模型/无工具/无记忆/无审批/工具异常/模型异常，全部转成文本，
   没有一条路径会把异常抛给宿主。这部分设计是对的，不用动。
2. **模型调用是一次性阻塞**（`ModelService.complete` 返回完整 `AgentReply`），**不是流式**。
   UI 上那个流式光标没有数据源——不是 UI 的锅。
3. **工具串行且无超时**（`execute` 里 `calls.map`）。一个卡住的 shell 能拖死整轮。
4. **轮数耗尽时静默截断**（`:112` break 之后直接进收尾）：模型不知道"我被截断了"，
   用户也不知道，只看到半成品。这比"3 轮太少"更隐蔽——**它连失败都不报告**。

---

## 结论 1：固定轮数上限是"语法性 kill-switch"，不是能力边界

**依据**：R4 把 agent 定义为"LLM 在循环里用工具"，适用于无法预测步数的任务，最大迭代次数只是
控制手段。R7 把这件事说得更狠：固定 `max_iterations` 是**syntactic kill-switch**，
对"答案是否还在变好"完全视而不见——简单输入过度消耗，困难输入被截断。
R7 的做法是语义早停（连续草稿的余弦距离 + patience window），其 **judge-free** 版本在
HotpotQA 上比固定轮数省 38% 运营 token 且质量持平；而"每轮都调判官"的质量门控版反而更贵。

**EV 现状**：`AgentBrainService.kt:276` — `MAX_TOOL_ROUNDS = 3`，注释写的是"stops a model that loops"，
但实现**没有检测是否真的在循环**，只是硬砍。

**问题**：一个"建目录→写三个文件→列出来"的任务，工具调用就 5 次，必然截断。

**改法**（按 R7 的取舍）：
1. 上限提到 16~24，同时加**总预算**（token / 墙钟 / 工具调用次数），由预算决定何时停；
2. 终止信号用**便宜的语义信号**（例如连续两轮的 assistant 文本高度相似 = 没有进展），
   **不要**每轮调判官——R7 明确说那条路更贵；
3. Oracle 结果显示"选哪一轮最好"比"何时停"更难，所以我们不做选择器，只做"别把好的那一轮丢掉"。

---

## 结论 2：终止必须带理由，并告知模型

**依据**：R7 的核心指控就是"不透明的终止"。一个不知道为何停止的 agent，下一轮无法调整策略；
用户也无法判断"是没做完还是做完了"。

**EV 现状**：`:112` 轮数耗尽后直接收尾，**没有任何提示**（既不给模型，也不给用户）。

**改法**：循环退出时区分三种理由——`MODEL_FINISHED`（无工具调用）/ `BUDGET_EXHAUSTED` /
`POLICY_STOPPED`（重复调用、审批拒绝等）——并把理由：
1. 放进 `AgentReply`（新增字段，UI 可显示"因预算用尽而停止"）；
2. 作为一条 system 消息回灌，让模型知道"你没有做完，可以只输出当前的进展"。

---

## 结论 3：失败必须"复盘"，而不是原地重试

**依据**：R2 的核心是**言语强化**——把反馈转成自然语言反思，存进 episodic buffer，
下一轮带着反思重新决策。R6 指出 agent 失败主因之一是长程决策，而重复同一动作正是不做决策。

**EV 现状**：工具报错原文会作为 `TOOL` 消息回灌（`:119-127`），这是对的；但**没有任何重复检测**。

**问题**：模型对同一工具、同样参数反复调用（文件不存在还在重试），循环不干预，只会耗光轮数。

**改法**：
1. 记录每轮 `(工具名, 参数, 结果摘要)`；
2. 连续两轮同名同参且结果未变 → 中断当前轮，注入 system 消息：
   "你刚才用相同参数调用了 N，结果没有变化；换一种方法，或直接说明为什么无法继续"；
3. 反思文本写入 `agent-memory`，同一会话后续轮次可见。

---

## 结论 4：把难度从模型移到 harness

**依据**：R8 的主要发现是——**任务难度大部分是实例间共享的，可以从模型转移到 harness**
（通过定制指令、工具、编排循环）。他们用 meta agent 自动发现 harness 适配，
在 7 个业务任务 × 3 个小模型家族上，21 个组合里 16 个显著提升，最好的小模型 agent
达到大模型 89.7% 的性能、**4% 的成本**。并指出：**重复性工作流**受益最大。

**对 EV 的意义**：EV 接的是 `deepseek-flash` 这类小模型，正好是 R8 的目标场景。
这给了我们一个明确取向——**与其换更大的模型，不如把 harness 打磨好**：
固定的分步指令、更明确的工具描述、把"该按什么顺序做"写进编排而不是指望模型自己悟。

**改法**：
1. 系统提示里给"工具使用顺序"的启发式（先 list 再 read、写文件前先确认目录）；
2. 把常见任务的固定步骤做成显式的 workflow 分支（R4 的 prompt chaining / routing），
   而不是全交给模型自由发挥；
3. 每次失败案例都反问：这是模型能力不足，还是 harness 没给它足够的信息/工具？

---

## 结论 5：工具接口（ACI）比模型选型更影响成功率

**依据**：R3 的主要发现是**接口设计本身**显著决定 agent 表现——同一模型换一套 ACI，
SWE-bench 上差距很大。R4 附录 2 给了同方向经验：把"相对路径导致的错误"改成**强制绝对路径**
之后，模型使用该工具变得零错误；并建议对工具做 poka-yoke（让错误用法更难发生）。

**EV 现状**：`agent-tools` 四个工具（read / write / list / delete）参数在
`StorageToolSpecs.kt`；`agent-shell` 每次调用需审批。

**改法**：
1. 文件工具统一要求**绝对路径**，相对路径在工具层拒绝并回完整绝对路径提示；
2. 错误信息结构化：`{原因, 当前状态, 建议动作}`，不要只回 `"失败"`；
3. 破坏性操作（delete / shell 写入）要求显式 `confirm` 参数，配合现有审批。

---

## 结论 6：上下文要"压缩 + 笔记"，不是只截断

**依据**：R5 指出 context rot——上下文越长，模型对其中信息的准确召回越差，所以上下文是
**有限资源**。三种长任务手段：compaction、structured note-taking、sub-agent。
R5 明确把"清理旧的工具结果原文"列为**最低成本、最安全**的压缩手段。

**EV 现状**：`HistoryTrim.kt` 负责裁剪（20 轮 / 24000 字符），工具结果截断 8000 字符。

**问题**：截断是"丢弃"，不是"压缩"。被砍掉的部分不会以摘要保留，长任务里模型会丢失
已经确认过的结论（比如"用户要求的输出目录是 X"）。

**改法**：
1. 超阈值时先做**工具结果清理**（旧 TOOL 原文换成一行摘要），几乎无损；
2. 再做 compaction：让模型把前文压成"已完成 / 未完成 / 关键约束"三段；
3. `agent-memory` 承担 note-taking：每轮把进度与结论落盘，重开会话可检索回注。

---

## 结论 7：推理轨迹要显式

**依据**：R1 除了成功率，还强调 reasoning trace 带来**可解释性与可信度**；R4 把"显式展示
agent 的规划步骤"列为三条核心原则之一（transparency）。

**EV 现状**：`AgentEvent` 能表达工具调用与结果，UI 的 `EvChatBubble` 有 `events` 参数；
但模型的"思考/计划"没有独立通道。

**改法**：把每轮的简短计划（ReAct 的 Thought）作为事件流的一部分展示，而不是只显示工具调用。

---

## 结论 8：需要一组可复现任务来衡量"是否像 agent"

**依据**：R6 用 8 个环境做多维评测，并指出失败集中在长程推理与指令遵循——这类问题不靠换模型
解决，靠循环与接口设计解决。没有评测无法判断改造是否有效。

**改法**：在 `plugins/agent-brain` 的测试里固定一组任务：

| 级别 | 任务 | 期望 |
|---|---|---|
| L1 | 单工具：读一个文件并总结 | 1 轮内完成 |
| L2 | 两工具：列目录后读其中一个 | ≤3 轮 |
| L3 | 多步同构：建目录、写三个文件 | ≤8 轮，全成功 |
| L4 | 需要试错：先写错路径，收到错误后自行纠正 | ≤8 轮，含一次失败恢复 |
| L5 | 长任务：10+ 文件批量处理 | 不超预算，且历史压缩后仍记得初始约束 |

L3 是当前 3 轮上限的**必挂项**，也是最直接的验收标准。

---

## 对 EV 的改造顺序

| 顺序 | 改动 | 依据 | 验收 |
|---|---|---|---|
| 1 | 轮数 3 → 20，加总预算（token/时间/次数），终止带理由并回灌模型 | R7, R1, R4 | L3 通过；停止时能说出原因 |
| 2 | 重复调用检测 + 反思注入 | R2 | L4 通过 |
| 3 | 工具结果分级清理 + compaction | R5 | L5 在预算内完成 |
| 4 | 文件工具绝对路径 + 结构化错误 + confirm | R3, R4 | L2 零路径类错误 |
| 5 | 计划轨迹进事件流 | R1, R4 | UI 能看到每轮意图 |
| 6 | 评测任务集落进测试 | R6 | L1–L5 可重复跑 |
| 7 | 系统提示补工具使用顺序启发式；常见任务改 workflow 分支 | R8 | 同任务轮数下降 |

前两项做完，"3 轮就停"和"停了不说"这两个症状会消失；第 3、4 项决定它能不能撑住长任务；
第 7 项是小模型场景下性价比最高的一步。
