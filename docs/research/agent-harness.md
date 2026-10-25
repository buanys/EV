# Agent / Harness 调研笔记

写这份文档的原因：EV 现在的 `agent-brain` 循环只有 **3 轮**、串行调工具、没有预算和重复检测，
表现是"一句话它动两个工具就停了"。要改得有理有据，先把公开文献里已经验证过的结论摊开，
再逐条对到 EV 的现状上。

范围限定：只收**能落到 EV 代码里**的结论，不收泛泛的综述。

## 参考文献

| # | 文献 | 关键贡献 |
|---|---|---|
| R1 | [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/abs/2210.03629) (Yao et al., ICLR 2023) | 推理轨迹与动作交替生成；与只推理或只行动相比，ALFWorld +34%、WebShop +10% 成功率 |
| R2 | [Reflexion: Language Agents with Verbal Reinforcement Learning](https://arxiv.org/abs/2303.11366) (Shinn et al., NeurIPS 2023) | 用自然语言复盘替代权重更新；HumanEval pass@1 91% 对 GPT-4 的 80% |
| R3 | [SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering](https://arxiv.org/abs/2405.15793) (Yang et al., NeurIPS 2024) | **接口设计**本身显著影响 agent 成功率；提出 ACI（agent-computer interface）概念 |
| R4 | [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) (Anthropic, 2024) | workflow 与 agent 的架构分界；agent = "LLM 在循环里用工具"；工具文档要像给初级开发者写 docstring |
| R5 | [Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) (Anthropic, 2025) | context rot；compaction / structured note-taking / sub-agent 三种长任务手段 |
| R6 | [AgentBench: Evaluating LLMs as Agents](https://arxiv.org/abs/2308.03688) (Liu et al., ICLR 2024) | 8 个交互环境上的评测；失败主因是长程推理、决策与指令遵循，而不是模型知识 |

---

## 结论 1：轮数上限是安全阀，不是能力边界

**依据**：R4 把 agent 定义为"LLM 在循环里用工具"，并明确指出 agent 适用于"无法预测所需步数、
没法硬编码固定路径"的任务；同时建议保留最大迭代次数作为**控制手段**。R1 的实验中，单次推理-行动
往往不足以完成任务，多步轨迹是常态。

**EV 现状**：`plugins/agent-brain/.../AgentBrainService.kt:276` — `MAX_TOOL_ROUNDS = 3`。

**问题**：3 轮意味着"模型-工具-模型-工具-模型"最多三次模型调用。一个"建目录→写三个文件→列出来"
的任务，光工具调用就 5 次，必然截断，且截断后给出的是半成品答案，用户看到的就是"它做一半停了"。

**改法**：上限提到 16~24 轮，同时引入**总预算**（token 上限 + 墙钟超时 + 工具调用总次数），
由预算而不是固定轮数来决定何时停。轮数只是防止无限循环的最后一道闸。

---

## 结论 2：失败必须"复盘"，而不是原地重试

**依据**：R2 的核心是**言语强化**——把任务反馈转成自然语言反思，存进 episodic buffer，
下一轮带着这段反思重新决策；不更新权重就能显著提升。R6 指出的 agent 失败主因之一是长程推理与决策，
而重复同一动作正是不做决策的典型表现。

**EV 现状**：工具报错原文会作为 `AgentRole.TOOL` 消息回灌（`AgentBrainService.kt:119-127`），
这是对的，但没有任何"你刚才重复了"的检测。

**问题**：模型在两轮里对同一工具、同样参数反复调用（文件不存在还在重试），循环不会干预，
只会把剩余轮数耗光。R2 的做法是在这种时候插入一段反思文本，而不是继续喂同样的失败结果。

**改法**：
1. 记录每轮的 `(工具名, 参数, 结果摘要)`；
2. 连续两轮同名同参且结果未变 → 中断当前轮，向上下文注入一条 system 消息：
   "你刚才用相同参数调用了 N，结果没有变化；换一种方法，或直接说明为什么无法继续"；
3. 反思文本写入 `agent-memory`，让同一会话后续轮次能看到。

---

## 结论 3：工具接口（ACI）比模型选型更影响成功率

**依据**：R3 的主要发现是**接口设计本身**显著决定 agent 表现——同一模型换一套 ACI，SWE-bench 上
差距很大。R4 的附录 2 给了同方向的具体经验：把"用相对路径导致的错误"改成**强制绝对路径**之后，
模型使用该工具变得零错误；并建议对工具做 poka-yoke（让错误用法更难发生）。

**EV 现状**：`agent-tools` 的四个工具（read / write / list / delete）参数在
`plugins/agent-tools/.../StorageToolSpecs.kt`。`agent-shell` 每次调用需要审批。

**问题**：需要逐一核对的是——参数是否接受相对路径？错误信息是否包含"下一步该怎么做"？
删除是否有二次确认参数？这些都属于 ACI 而不是模型能力问题。

**改法**（按 R3/R4 的经验排序）：
1. 文件工具统一要求**绝对路径**，相对路径直接在工具层拒绝并给出完整绝对路径提示；
2. 错误信息结构化：`{原因, 当前状态, 建议动作}`，不要只回 `"失败"`；
3. 破坏性操作（delete / shell 写入）要求显式 `confirm` 参数，配合现有审批服务。

---

## 结论 4：上下文要用"压缩 + 笔记"，不是只截断

**依据**：R5 指出 context rot —— 上下文越长，模型对其中信息的准确召回越差，因此上下文是**有限资源**。
三种长任务手段：compaction（摘要压缩）、structured note-taking（把进度写到上下文之外的记忆）、
sub-agent（子代理只回摘要）。R5 明确把"清理旧的工具结果原文"列为**最低成本、最安全**的压缩手段。

**EV 现状**：`plugins/agent-brain/.../HistoryTrim.kt`（63 行）负责裁剪，`agent-memory` 负责
`remember(...)`；工具结果有 `MAX_TOOL_RESULT_CHARS` 截断。

**问题**：截断是"丢弃"，不是"压缩"。历史被砍掉的部分不会以摘要形式保留，长任务里模型会丢失
已经确认过的结论（比如"用户要求的输出目录是 X"）。

**改法**：
1. 历史超过阈值时，先做**工具结果清理**（把旧的 TOOL 原文换成一行摘要），这一步几乎无损；
2. 再做 compaction：让模型把前文压成"已完成 / 未完成 / 关键约束"三段的摘要；
3. `agent-memory` 承担 note-taking：每轮把进度与结论落盘，重开会话时可检索回注。

---

## 结论 5：推理轨迹要显式，这是"像 agent"的观感来源

**依据**：R1 除了成功率，还强调 reasoning trace 带来**可解释性与可信度**；R4 把"显式展示 agent
的规划步骤"列为三条核心原则之一（transparency）。

**EV 现状**：`AgentEvent` 已经能表达工具调用与结果，UI 侧 `EvChatBubble` 有 `events` 参数；
但模型的"思考/计划"没有独立通道。

**改法**：把每轮的简短计划（ReAct 的 Thought）作为事件流的一部分展示，而不是只显示工具调用。
这同时解决"用户不知道它在干什么"的体验问题。

---

## 结论 6：需要一组可复现任务来衡量"是否像 agent"

**依据**：R6 用 8 个环境做多维评测，并明确指出失败集中在长程推理与指令遵循——这类问题不靠
换模型解决，靠循环与接口设计解决。没有评测就无法判断改造是否有效。

**改法**：在 `plugins/agent-brain` 的测试里固定一组任务，从易到难覆盖：

| 级别 | 任务 | 期望 |
|---|---|---|
| L1 | 单工具：读一个文件并总结 | 1 轮内完成 |
| L2 | 两工具：列目录后读其中一个 | ≤3 轮 |
| L3 | 多步同构：建目录、写三个文件 | ≤8 轮，全成功 |
| L4 | 需要试错：先写错路径，收到错误后自行纠正 | ≤8 轮，包含一次失败恢复 |
| L5 | 长任务：10+ 文件批量处理 | 不超预算，且历史压缩后仍记得初始约束 |

L3 是当前 3 轮上限的**必挂项**，也是最直接的验收标准。

---

## 对 EV 的改造顺序

| 顺序 | 改动 | 依据 | 验收 |
|---|---|---|---|
| 1 | 轮数 3 → 20，加总预算（token/时间/次数） | R1, R4 | L3 通过 |
| 2 | 重复调用检测 + 反思注入 | R2 | L4 通过 |
| 3 | 工具结果分级清理 + compaction | R5 | L5 在预算内完成 |
| 4 | 文件工具绝对路径 + 结构化错误 + confirm | R3, R4 | L2 零路径类错误 |
| 5 | 计划轨迹进事件流 | R1, R4 | UI 能看到每轮意图 |
| 6 | 评测任务集落进测试 | R6 | L1–L5 可重复跑 |

前两项做完，"3 轮就停"这个最刺眼的症状会消失；后四项决定它能不能撑住长任务。
