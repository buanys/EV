# `docs/sdk/` — 宿主能力面与写插件的入口

这个文件夹存在的理由只有一个：**让"写一个 ev 插件"变成可查、可验证、可自动化的动作。**

现阶段它服务三类读者：

| 读者 | 从这里开始 |
|---|---|
| 人（写 `ev-*` 插件） | [host-services.md](host-services.md) → [versioning.md](versioning.md) |
| AI（后续让 EV 自己写插件） | [catalog.json](catalog.json)（机器可读）→ [host-services.md](host-services.md) |
| 改宿主的人 | 先改 [host-services.md](host-services.md)，再改代码 |

## 文件

| 文件 | 是什么 | 谁维护 |
|---|---|---|
| [host-services.md](host-services.md) | **权威文档**：40 个服务 Key、15 个权限、四种送达方式、缺什么 | 改宿主时同步 |
| [catalog.json](catalog.json) | 同一份事实的机器可读形式（服务 / 权限 / 送达方式 / 缺口） | 跟 md 一起改 |
| [versioning.md](versioning.md) | 宿主 API 版本门：插件怎么声明兼容范围、宿主怎么拒 | 改契约时同步 |
| [publishing.md](publishing.md) | 怎么把插件开成独立工程、为什么不用 mavenLocal | 改构建时同步 |

## 与其它文档的关系

- `docs/newdocs/规范/ev-manifest-spec.md` — **插件的 manifest 长什么样**（格式规范）
- `docs/extension-points.md` — 扩展点清单（概念层）
- `docs/adr/` — 决策记录
- **本文件夹** — 宿主**现在实际**给得出来什么

一句话分工：`ev-manifest-spec` 管"怎么写"，本文件夹管"能写什么"。

## 为什么不用 `docs/` 根目录

`docs/` 根是给"项目怎么运转"看的（架构、发布、可复现性）。
`docs/sdk/` 是给"**外部输入**"看的——以后 AI 拿到的上下文应当是这个文件夹，
而不是整个 `docs/`。边界清楚，检索成本才低。
