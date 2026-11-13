# ev-bot：方向判断

- 日期：2026-11-08
- 依据：三份独立研究（Super-agent 原文与原型、六个 QQ 拟人 bot 的实现、EV 现有代码链路）
- 结论先行：**ADR-0010 的前提被代码否掉了。** 人格层和执行层在 EV 里本来就是分开的——
  `systemPrompt` 每轮独立注入、不落盘、不进 transcript、不进记忆文件。
  真正缺的不是双层架构，是三件更小的事：**人格从哪来、什么时候开口、记得住什么**。

---

## 一、三个被代码推翻的假设

### 1.1「人格污染执行上下文」——不成立

`plugins/feature/agent-brain/.../TurnPrompt.kt`：

```kotlin
characterPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
    stable += PromptLayer(PERSONA_ID, "角色", it, PERSONA_PRIORITY)
}
```

人格是一个独立 `PromptLayer`，priority 60，每轮现拼。`assemble()` 有
`MAX_SYSTEM_PROMPT_CHARS = 8_000` 硬预算，超了从最小层整层丢弃（不截断）。
它不进 `state.inputs` 的历史，`AgentBrainService:281` 只记原始 user 文本。

`ChatSessionController` 的 KDoc 写得更明确：

> The prompt is *not* added to the bubble text and the brain does not store it,
> so a role changes what the model sees without touching the transcript or the memory file.

**所以「两种 agent 分离」要解决的问题，EV 在机制上已经解决了。** 代价是占 8k 预算的一部分，
不是"污染"。这削弱了 ADR-0010 的全部必要性论证。

### 1.2 通道已经接好，只是没有接上

`ChatSessionController.kt:140`：

```kotlin
systemPrompt = null,
```

而同一函数的 KDoc 上一段写着"The turn carries the session's character as `systemPrompt`
when one is selected"。

这是**文档先行、代码没跟上**：角色通道做完了，角色层后来被撤（`agent-characters` /
`agent-skills` 都不在模块列表里）。`ChatSession.characterId` / `skillId` 在 `sessions.json`
里**已经能读写**（`ChatSessionDocuments.kt:56-58, 72-73`），**全仓没有一个写入点**。

### 1.3 ev-ui 不是可靠守门人

人格判断放在 `ChatSessionController` 里，意味着 headless / 远程入口（agent-web console、
桌面快捷方式）不经过它，人格只在界面里生效。要全链路，插入点是 `AgentBrainService.turn()`
（在 :251 造 `TurnState` 与 :262 造 userMessage 之间）。改动文件数差不多，但侵入执行链。

---

## 二、Super-agent 该抄哪一半

那篇文章里其实是**两个独立机制**，被媒体混成了一件：

| 机制 | 解决什么 | 手机上成立吗 |
|---|---|---|
| Router / Worker 双层 | 并行任务调度（人只能盯 3–5 个会话） | **不成立**：GUI 是单例资源，两个 worker 同时点屏幕必然互踩 |
| 异步 ReAct Loop（start/stop） | 执行长任务时不阻塞对话 | **刚需**：一次 GUI 操作 2–10 秒，同步阻塞等于交互死掉 |

结论：**要的是异步执行，不是双层调度。**

其余可迁移的，按性价比排：

1. **两级过滤**（程序规则先筛 → 模型再判断）。手机通知噪音比群聊更高，快路径要比论文更重。
2. **Trigger 幂等**（稳定 key + disposition）。Android 会重复投递通知、进程反复重启，
   没有这层会重复执行动作。
3. **Inline & Spill + 链式召回**。不需要向量库——SQLite + 稳定 ID + 分页游标就够，
   而且进程被杀后上下文仍在磁盘上。但我们的上下文体量还没到，先记着。
4. **Mandate / Policy 版本化 + `observe` 默认档**。先看它"本来会做什么"，一周后再开自动。

**明确不可迁移**：云端存算分离、多 worker 并行、模型判进展的常驻 Watchdog（Doze 会杀）、
"订阅扇出自行解决冲突"（前提是共享可读环境，手机没有）。

---

## 三、QQ bot 生态里，真正起作用的是三件土事

看完六个项目，真正决定"像不像人"的不是情绪系统，是这三样：

### 3.1 记忆的写入纪律（最值钱）

molly 的做法：**9 条闸门 → 0.7 相似度覆盖合并 → 加权召回 → 敢返回空**。

- 闸门拦掉：无身份、辱骂、少于 4 字、自我叙述、元叙述、碎片、空话、重复。
  规则自己抛异常时**一律放行**（宁可放过不可错杀）。
- 同人同主题相似度 ≥0.7 时**覆盖旧条**，而不是新增——挡掉"同一件事存三遍"。
- 召回时**没有两字以上硬命中就返回空数组**，宁可说想不起来，也不拿不相干记忆凑数。

最后这条是最反直觉、也最影响体验的：拟人 bot 最大的破功不是"忘了"，是"记岔了还硬说"。

### 3.2 主动打扰的预算

大肥鱼的 `1/120/5/1`，含义是：

| 参数 | 值 | 作用 |
|---|---|---|
| daily_limit | 1 | 一天最多主动联系 1 次 |
| cooldown | 120 min | 两次至少隔 2 小时 |
| owner_quiet | 5 min | **主人刚说过话，先让出 5 分钟** |
| unanswered_limit | 1 | 当天最近一次主人消息之后，最多主动 1 次 |

比参数更值钱的是**账本**：SQLite 事务先预留额度再发网络请求；状态机
`reserved → dispatching → sent / reported / uncertain / cancelled`；发送中被打断记
`uncertain` 且**不补发**。

"主人正在聊天时让位"这一条，比任何心情系统都更能让人感觉"她懂事"。

### 3.3 一条反直觉的实测

molly 的上下文审计：一轮完整 prompt 6,869 token，其中

- system 占 **82.5%**
- 记忆段占 31.2%
- 历史只占 13.2%

把记忆窗口从 48+16 降到 32+12，省 934 token（-13.6%）。

**结论：别装 tokenizer、别换向量库、别激进压历史。主战场是人设和记忆段。**

这一条直接推翻了很多人的优化直觉，包括我原本想推的"压缩历史"。

---

## 四、我的方案

### 4.1 ev-bot 的定位：不是上级，是三个旁路服务

"上级"这个模型要求下属有独立目标、能并行推进、需要协调——手机上一个都不满足：
执行者只有一个，GUI 独占，任务串行。**"派活"在手机上是伪需求。**

真正该做的是三个正交的服务：

| 服务 | 回答的问题 | 依赖 |
|---|---|---|
| `PersonaService`（`agent/persona`） | 这一轮用什么语气、说不说 | 人设文件 + 会话 |
| `ProactiveScheduler`（`agent/proactive`） | 什么时候主动开口、说几次 | 通知权限 + 日历 + 使用统计 |
| 记忆纪律（扩 `agent-memory`） | 记什么、合并什么、敢不返回什么 | 已有 MemoryService |

一个 Gradle 模块 `plugins/feature/ev-bot`，内部三个 ServiceKey。
插件数只加 1（17 → 18），不会变成第二个 `EvUiPlugin`（那个已经 1373 行了）。

### 4.2 阶段 0：半天，先证明人格有用

1. 写一个人格 md（照 molly 的 persona-guide：写"你就是"不写"你扮演"；
   写可判定规则不写形容词；必须写"允许沉默"和"什么时候不这样"）。
2. `ChatSessionController.kt:140` 把 `null` 换成一个读文件的调用。
3. 同一句话，有/无人格各跑五轮，记长度、语气、工具调用参数、失败时的措辞。

**这一步的先决价值**：在造任何契约之前，先确认这件事值得做。如果人格只是让回复变长，
后面三步都该停。

### 4.3 阶段 1：一周，主动性

这是 `docs/coverage-tasks.md` 里唯一标红且**不需要人格**的能力。

- `WorkManager` + `AlarmManager`，**不要常驻定时器**（Doze 会掐）
- 机会窗口评估：亮屏、解锁、充电、通知到达时各评一次，而不是"每 5 分钟醒一次"
- 四参数 + SQLite 账本（照 3.2）
- 输出是本地通知，不是消息——"通知已发出"和"用户看到"必须分开记
- 低电量 / 省电模式 / 漫游时额度直接降为 0

### 4.4 阶段 2：一个月，记忆纪律

`agent-memory` 已有（17 文件），缺的是**三条纪律**，全是增量：

1. 写入闸门（拦无内容、自我叙述、过短、重复）
2. 同主题相似度覆盖合并
3. 召回敢返回空
4. 相对日期归一（"明天"入库时写成"明天（11 月 9 日）"）——这是最容易被忽略的细节

不动 `agent-brain`，不动 `host-api`。

### 4.5 阶段 3：有真实需求再做

异步 ReAct Loop。触发条件是"用户正在聊天，后台任务还要继续跑"这个场景真的出现。
技术点：job 持久化、进程被杀后补偿扫描、前台服务保活。

### 4.6 明确不做

- **Executor 拆分**（ADR-0010 阶段二）。没有证据表明需要，而代价是拆 1703 行。
- **多 Worker 并行**。GUI 单例，做了就是互踩。
- **向量库**。739 条规模已被 molly 自己的审计否掉，手机上更是负资产。
- **Inline & Spill / 链式召回**。设计对，体量未到。
- **情绪状态机（心情/精力/好感度）**。研究显示多数项目里它只是 prompt 里的形容词，
  唯一真正影响"说不说"的只有 Nimu，而那份文档自己的里程碑表还是 ⬜。

---

## 五、ADR-0010 怎么办

需要改三处：

1. **背景第 3 条删掉**（"人格与执行共用一条上下文"被 `TurnPrompt.kt` 否掉）。
2. **决策二的阶段一降级**：`PersonaService` 不是必需的，先用 `systemPrompt` 直传验证。
3. **阶段二标记为"不推荐执行"**，保留为备选路径。

建议状态改成 `superseded by this note`，或者直接删掉重写——它现在描述的是一栋不需要盖的楼。

---

## 六、风险和不确定

1. **人格是否有用，我没有证据。** 这是阶段 0 存在的全部理由。
2. **主动性会打扰人。** 手机上的打扰成本远高于聊天软件，`daily_limit` 建议从 1 开始，
   一周后再谈放宽。
3. **通知权限是硬门槛。** 拿不到就只剩"用户回来时才可能说话"，主动性退化。
4. **`ServiceDirectory` 是覆盖写，没有 `lookupAll`。** 将来若要"系统人格 + 用户人格"叠加，
   契约必须改成 registry，不是单个 service。
5. **Dispatch 会污染"用户说了什么"。** 如果以后做人格改写输入，brain 记录的是改写后的文本，
   transcript 与存储从此不同。v1 应避免改写输入。

---

## 七、如果只做一件事

**做阶段 0。**

三行代码，一个人格文件，一个下午。它能回答"这件事值不值得做"，而这个问题目前没有任何证据。
其余四节的前提，都建立在这个答案上。
