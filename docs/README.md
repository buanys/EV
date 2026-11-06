# EV 文档索引

找文档先来这里。四类：**现状**（系统现在长什么样）、**决策**（为什么这么定）、
**规则**（必须遵守的）、**档案**（历史工作记录，只读）。

**新对话 / 新成员入口：[project-overview.md](project-overview.md)**——项目交接档案，
2026-11-06 重写，是本文之外唯一可以当"当前事实"读的现状文档。

> 冲突时以 `project-overview.md` 为准（现状）与 `architecture.md`（结构）为准。

## 现状

| 文档 | 内容 | 状态 |
|---|---|---|
| [project-overview.md](project-overview.md) | **项目现状总览**：是什么、仓库地图、六阶段进度、17 个插件、门禁、已知缺口、操作经验、踩过的坑 | ✅ 2026-11-06 |
| [architecture.md](architecture.md) | **架构总览**：四层、真实依赖图、运行期类解析、宿主职责、不承诺什么 | ✅ 2026-11-06 重写 |
| [build-on-device.md](build-on-device.md) | **在这台手机上编译 EV**：转译层、容器、离线构建、验产物、推设备与 12 条故障对照 | ✅ |
| [plugin-ui-composition.md](plugin-ui-composition.md) | 插件 UI 如何组合进宿主界面 | ✅ |
| [release-signing.md](release-signing.md) | 发布签名方案 | ✅ |
| [extension-points.md](extension-points.md) | 扩展点机制设计方案 | ⚠️ 草案，未实施 |
| [research/on-device-compiler.md](research/on-device-compiler.md) | **设备端编译 Kotlin（已实测）**：源码→class→dex→插件→运行，含 8 个 ART 兼容坑 | ✅ |
| [research/agent-harness.md](research/agent-harness.md) | agent harness 调研 | 参考 |

## 决策（ADR）

11 篇。编号有三组重复（0001/0002/0004 各两篇），是历史原因，看文件名区分。

| 编号 | 决策 | 一句话结论 |
|---|---|---|
| [0001](adr/0001-in-place-refactor.md) | 原地演进 | 不开新仓，tag `pre-refactor` 为起点，主分支始终可构建可装机 |
| [0001](adr/0001-kernel-consumption.md) | 内核消费方式 | 同仓源码 + 冻结门禁 + 发布快照，不拆仓 |
| [0002](adr/0002-host-runtime-split.md) | 宿主拆分 | 拆成装配 / 运维 / 路由 / 生命周期四块 |
| [0002](adr/0002-plugin-interface.md) | 插件接口 | 插件直接实现 `org.cordis.Plugin`，不加适配层 |
| [0003](adr/0003-permission-scope.md) | 权限范围 | 声明 + 授权 + 门控 + 审计，**不承诺 OS 级沙箱** |
| [0004](adr/0004-on-device-forge.md) | 设备端锻造 | 编译器独立成能力包（默认关闭）；安装契约由宿主提供 |
| [0004](adr/0004-runtime-budget.md) | 执行预算与内存压力 | 宿主只回答"能不能开、多久必须结束"；隔离档位待 5c |
| [0005](adr/0005-system-prompt-plugin.md) | 系统提示词做成插件 | 分层 + 可裁剪 |
| [0006](adr/0006-session-event-log.md) | 会话事件流 | 事件流是历史的唯一真相源，memory 是派生物 |
| [0007](adr/0007-approval-policy-and-guards.md) | 审批分层 | "策略"预判 + "人工"确认两层，工具前缀硬规则 |
| [0008](adr/0008-trust-by-capability-surface.md) | 未签名的信任 | 按**能力面**判定，不按字节 |

新决策开新编号，旧 ADR 只追加状态，不改写历史。

## 规则

| 文档 | 内容 |
|---|---|
| [newdocs/最高规则/ev-final-rules.md](newdocs/最高规则/ev-final-rules.md) | **最高规则（验收标准）**：方向、树形结构、内核宿主插件关系、工程约束。规则与代码冲突时以规则为准；改规则先写 ADR |
| [kernel-freeze.md](kernel-freeze.md) | 内核冻结章程：范围、三条规则、例外流程、解冻条件 |
| [design/EV-UI设计规范.md](design/EV-UI设计规范.md) | UI 设计规范 v1.0（草案；作者说过"不一定正确"，实际验收以四条设计准则为准） |

**最高规则里未落地的条目**（逐条对照见 `project-overview.md` §8）：**只剩信任三档（5c）**。
（凭据归宿主 §8.2、依赖版本 §8.3、失败计分与回滚 §8.4 已于 2026-11-06 落地。）

另有一条"宿主要包一层事件分派"——它针对的用法目前不存在（全仓零事件订阅，插件之间走
`ServiceDirectory` 查表），所以不是欠实现，是规则与实现脱节，见 §8.5。

## 草案与规划

| 文档 | 内容 |
|---|---|
| [newdocs/草案/](newdocs/草案/) | UI 审计、设计资产、待定决策 |
| [newdocs/规范/ev-manifest-spec.md](newdocs/规范/ev-manifest-spec.md) | 插件清单规范 |
| [newdocs/规范/ev-plan.md](newdocs/规范/ev-plan.md) | 计划稿 |
| [newdocs/ev-ui-plan.md](newdocs/ev-ui-plan.md) | ev-ui 计划 |

## 档案（历史工作记录，只读）

| 目录 | 内容 |
|---|---|
| [archive/2026-10-24-arch-hardening/](archive/2026-10-24-arch-hardening/) | 架构加固总计划（T01–T31）与真机验收记录 |
| [archive/2026-10-24-ev-foundation/](archive/2026-10-24-ev-foundation/) | EV 地基阶段进度台账 |

档案里的"计划项""待办"都是历史快照，**不作为现状依据**。
