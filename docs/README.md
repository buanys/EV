# EV 文档索引

找文档先来这里。四类：**现状**（系统现在长什么样）、**决策**（为什么这么定）、
**规则**（必须遵守的）、**档案**（历史工作记录，只读）。

**新对话/新成员入口：[project-overview.md](project-overview.md)**（项目交接档案）。

## 现状

| 文档 | 内容 |
|---|---|
| [project-overview.md](project-overview.md) | 项目总览：是什么、仓库地图、铁律、当前状态、路线图 |
| [architecture.md](architecture.md) | 四层架构总览：内核 / 宿主 / SDK / 插件，真实依赖图，职责边界 |
| [plugin-ui-composition.md](plugin-ui-composition.md) | 插件 UI 如何组合进宿主界面 |
| [release-signing.md](release-signing.md) | 发布签名方案 |
| [extension-points.md](extension-points.md) | 扩展点机制设计方案（**草案，未实施**）：声明式插件挂载 |
| [research/on-device-compiler.md](research/on-device-compiler.md) | **设备端编译 Kotlin（已实测）**：手机离线完成 源码→class→dex→插件→运行，含 8 个 ART 兼容坑与修法 |
| [research/agent-harness.md](research/agent-harness.md) | agent harness 调研 |

## 决策（ADR）

| 编号 | 决策 | 结论 |
|---|---|---|
| [0001](adr/0001-kernel-consumption.md) | 内核消费方式 | 同仓源码 + 冻结门禁 + 发布快照，不拆仓 |
| [0002](adr/0002-plugin-interface.md) | 插件接口 | 插件直接实现 `org.cordis.Plugin`，不加适配层 |
| [0003](adr/0003-permission-scope.md) | 权限范围 | 声明 + 授权 + 门控 + 审计，不承诺 OS 级沙箱 |

新决策开新编号，旧 ADR 只追加状态，不改写历史。

## 规则

| 文档 | 内容 |
|---|---|
| [kernel-freeze.md](kernel-freeze.md) | **内核冻结章程**：冻结范围、三条规则、例外流程、解冻条件 |
| [design/EV-UI设计规范.md](design/EV-UI设计规范.md) | **EV-UI 设计规范（v1.0 草案）**：颜色四级阶梯、字体/间距/圆角令牌、液态运动语言、组件与页面布局、实施路线 |

## 档案（历史工作记录，只读）

| 目录 | 内容 |
|---|---|
| [archive/2026-10-24-arch-hardening/](archive/2026-10-24-arch-hardening/) | 架构加固总计划（T01–T31，已完成）与真机验收记录、截图证据 |
| [archive/2026-10-24-ev-foundation/](archive/2026-10-24-ev-foundation/) | EV 地基阶段进度台账 |

档案里的"计划项""待办"都是历史快照，**不作为现状依据**；现状以 architecture.md 为准。
