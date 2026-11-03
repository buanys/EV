# 记忆三层设计（计划）

状态：**草案，待确认**。本文只描述要做什么、为什么这么做；实现细节以落地代码为准，
偏差写回本文。

日期：2026-11-03

## 0. 目标与非目标

**目标**

- 跨会话记住稳定事实，而不是把整段聊天塞进上下文。
- 全链路可追踪：L3 里的任意一行，都能回到 L2 条目，再回到 L1 的原话与时间。
- 提炼失败可恢复：不重复提炼，也不丢一段对话。

**非目标（v1 明确不做）**

- 向量检索 / embedding。
- 跨设备同步。
- 改动内核（`core`/`loader`）。全部在插件层与 `agent-api` 契约层完成。
- 替换现有的会话历史（那是 L0，见下）。

## 1. 分层

| 层 | 名字 | 内容 | 进上下文 | 存储 |
|---|---|---|---|---|
| L0 | 会话历史 | 对话原文（现有 `MemoryService`） | 按轮裁剪后 | UserDataStorage `memory/sessions/` |
| L1 | 记事本 | 一条事实/事件，带时间与出处 | 否，只在提炼时读 | UserDataStorage `memory/l1/` |
| L2 | 档案库 | 精炼条目，可长、可多、跨会话永久 | 否，按需检索 | UserDataStorage `memory/l2/` + 倒排索引 |
| L3-A | 工作区（agent 手写） | agent 自己用工具写的便签 | **是，每轮** | UserDataStorage `memory/l3/` |
| L3-D | 常驻区（提炼提升） | 从 L2 里选出的条目 | **是，每轮** | 同上，同一份 L3 文档 |

L0 与 L1 的区别：L0 是"说了什么"的流水，L1 是"记下的条目"。L1 不复制整段对话，
一条 L1 只描述一件事，用 `refs` 指回 L0 的消息 id。

**L3 只有一份，但分两个来源区**：工作区（agent 直接写）和常驻区（提炼从 L2 提升）。
两者共享 8000 字符预算，互相看得见，但编辑权限分离——见 §3。

## 2. 各层结构

### L1 — 记事本

```
id           l1_000123
sessionId    会话 id
seq          该会话内的消息序号（单调递增，游标用）
at           时间戳
actor        user | assistant | tool | system
text         一条事实的原文，≤200 字
refs         [L0 消息 id]
```

工具调用只记三项：**调了什么工具、参数摘要、结果摘要**。「解决了什么问题」是模型的
判断，不进 L1，留给 L2 归纳并标为推断。

写入方式：

- **主动**：agent 通过 `remember` 工具直接写；
- **被动兜底**：提炼时对未被主动记录的段落抽取。

### L2 — 档案库

```
id           m_000123
kind         preference | fact | task | convention
title        ≤40 字，检索结果里显示
body         正文，可长（建议 ≤500 字）
keywords     3–8 个检索词（提炼时产出，可空）
sources      [l1_...]        ← 追溯链，必填
sessions     [sess_...]
status       active | superseded | archived
supersedes   m_000101        ← 冲突时指向被替代的条目
impact       1.0             ← 模型可调，L3 排序用
createdAt / updatedAt
```

规则：

- **去重**：入库前按 `kind` + 关键词/标题重叠找候选，命中则合并 `sources`，不新增；
- **冲突**：新条目与旧条目矛盾时，新的指向旧的（`supersedes`），旧的转 `superseded`；
- **淘汰**：长期未被 L3 选中且未被引用 → `archived`（仍可检索，不进 L3 候选）。

### L3 — 常驻区

```
id        l3_000045
slot      user | env | task | convention
text      正文（进提示词）
src       m_000123（不进提示词，等于 source map）
zone      authored（agent 写） | derived（提炼提升）
pinnedAt  时间戳
partial   true 表示 text 是 L2 正文的截断
```

**预算只算 `text` 与槽位标题**。`src`、`zone`、`partial` 都不占那 8000 字符。

## 3. 模型专门写入区（L3-A）

这是 agent 自己的便签区：想立刻生效的一两句话，不用等提炼。

- 写入：`note(text)` 工具，直接进 L3-A；
- 可见性：提炼流程**读得到** L3-A（作为上下文），但**改不到**——提炼产出只进 L2，
  不回写、不删除 L3-A 的任何条目；
- 删除：只有 agent 自己的 `unnote(id)`，或用户手动清理；
- 容量：_建议_ 工作区上限 2000 字符，常驻区上限 6000，合计 8000。工作区满时拒绝写入
  并提示 agent 先 `unnote`，**不会**自动挤掉常驻区。

## 4. 提炼

### 4.1 触发

**且**条件：

1. 当前会话空闲 ≥ 15 分钟（没有在跑的一轮、没有待审批、没有未发送的草稿）；
2. 距上次覆盖点 ≥ 20 条消息（用户与模型的发言都计入）。

检查时机**不依赖后台定时器**（Android 会掐后台进程）：在应用回到前台、新消息发出、
本轮结束时各检查一次，满足就地异步执行。

### 4.2 两阶段状态机

```
开始前：写  pending(id, sessionId, fromSeq, toSeq, startedAt)
完成后：写  done(id, coveredUpTo = toSeq)，批次内 L2 条目转为可见
```

判定规则：

- `pending` 存在且 `now - startedAt > 超时阈值` → 判为**中断**；
- 中断批次：**整批作废**（按 `batchId` 删除未转正的 L2），下次重做这一段；
- 不做"续写"：半批数据的 `sources` 链不完整，比没有更糟。

### 4.3 幂等

覆盖范围用**消息序号**，不用"20 条"计数。会话内每条消息有单调递增 `seq`，
本次提炼处理 `(coveredUpTo, lastSeq]`。

- `coveredUpTo` 没推进 → 这批消息从未被覆盖，可重做；
- 推进了 → 绝不会被重复提炼。

「20 条」只是触发阈值，不参与边界计算。

### 4.4 提炼模型

独立配置项：**对话模型 / 提炼模型**。提炼默认用更便宜的那个（设置页可改）。
`ModelService.complete(model, messages)` 现成支持指定模型名。

输入：L1 的 `(coveredUpTo, lastSeq]` + 当前 L3-A 快照（只读）。
输出：若干 L2 条目，必须带 `sources`（指向参与的 L1 id）。

## 5. 检索

L2 不进上下文，靠工具按需查：

```
recall(query, limit)  → [{id, title, kind, updatedAt}]  （不返回全文）
read_memory(id)       → 完整 body + sources
```

**索引方案（v1）：中文 bigram 倒排。**

- 正文切成双字词（「用户偏好简洁」→ 用户 / 户偏 / 偏好 / 好简 / 简洁）；
- 维护 `term → [L2 id]` 映射，查询同样切词后取交集；
- 不依赖分词器、不依赖 SQLite FTS（Android 自带 `unicode61` 对中文无效）；
- 体量：几千条条目下内存索引足够，落盘为 JSON。

## 6. L3 的组装

每轮请求时从 L3 存储渲染，**模型只看到 `text`**，看不到 `src`、`zone`、`partial`。

```
【关于用户】
偏好回答先给结论，不要结尾总结
习惯用中文，少用列表

【环境】
插件目录 /sdcard/EV/mods，改完要重启 EV

【进行中】
记忆三层设计，L2 档案库 + L3 常驻区
```

- 顺序：`identity → L3 记忆 → 工具说明 → 风格 → (volatile 尾部: 时间)`；
- 放在**稳定前缀**，不放 volatile 尾部——L3 不随轮变化，放进 volatile 会让缓存每轮失效；
- 溢出：按「槽位权重 × impact × 最近使用」截断，被截的条目留在 L3 存储但标 `evicted`，
  下轮若权重回升可回到窗口。

## 7. 工具面

agent 能做的动作（全部经由 `ToolRegistry`）：

| 工具 | 作用 |
|---|---|
| `remember(text, kind)` | 新建 L2 条目（同时产生 L1 来源） |
| `note(text)` | 写入 L3 工作区，立即可见 |
| `unnote(id)` | 删除工作区条目 |
| `recall(query, limit)` | 检索 L2，返回摘要 |
| `read_memory(id)` | 读取 L2 全文 |
| `pin(memory_id)` | 把 L2 条目提升进 L3 常驻区 |
| `unpin(memory_id)` | 撤下 |
| `forget(memory_id)` | 归档（不物理删除） |

工具全部**非 dangerous**：只改插件自己的数据区，不碰系统。

## 8. 与现有契约的关系

- `MemoryService`（`agent/memory`）保持原样，继续负责 L0 会话历史；本文不改它的签名。
- 新增 `MemoryStoreService`（key 建议 `agent/memory-store`），负责 L1/L2/L3 与提炼。
- 新增契约全部放进 `agent-api`，带默认值、不加枚举值（避免已编译的 `when` 崩溃）。
- 实现放在 `agent-memory` 插件内（它已经持有 `UserDataStorage`），不新建插件。

## 9. 分阶段

**M1（先能用）**：L1 + L3 工作区 + `note` / `unnote`，每轮注入。
验证一个前提：**模型到底会不会主动记**。若它从不调用 `note`，后面两层都是空转。

**M2（结构化）**：L2 + `remember` / `recall` / `read_memory` + bigram 索引。

**M3（自动）**：提炼流程、两阶段状态机、`pin` / `unpin`、记忆页 UI。

## 10. 待定

- 提炼结果的置信度怎么评估（是否需要"原文摘录一致性校验"）。
- L2 的 UI 形态（列表 / 时间线 / 分类树）。
- 记忆是否要参与 L3 之外的上下文（例如工具结果里的相似记忆提示）。
- 用户正在思考的一个问题，待补。
