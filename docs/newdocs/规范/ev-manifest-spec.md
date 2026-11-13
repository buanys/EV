# 新 EV 插件清单规范（v1）

本文件定义插件清单的实际结构、字段类型、校验规则和演进规则。它是实现依据，不是概念说明。
清单一经定稿，只有在重构或新增功能时才改动，改动必须升 `manifestVersion`。

---

## 1. 位置与编码

- 文件名固定为 `ev-plugin.json`，放在插件包（zip 根目录）内，不允许放在子目录。
- 编码 UTF-8，无 BOM，不允许注释，不允许尾随逗号。
- 文件大小上限 64 KB。超过直接拒绝。
- 包内只允许存在一个 `ev-plugin.json`。出现多个，拒绝安装。

## 2. 清单不包含自身的哈希

- 清单不记录任何哈希，包括包自身的哈希。
- 包的 SHA-256 由宿主在安装时对整个包文件计算，记入安装记录。
- 用户批准绑定的是"包的 SHA-256"。插件任何一个字节变化（包括清单），都要重新批准。

## 3. 顶层字段

所有字段都是必需的，除非标注"可选"。顶层出现未在本规范中定义的字段，直接拒绝（严格模式，不忽略未知字段）。

| 字段 | 类型 | 规则 |
|---|---|---|
| `manifestVersion` | 整数 | 固定为 `1`。其他值拒绝。 |
| `id` | 字符串 | 见第 4 节。 |
| `name` | 字符串 | 展示名，1–40 个字符，允许中文。 |
| `version` | 字符串 | 严格语义化版本 `MAJOR.MINOR.PATCH`，不允许预发布后缀以外的修饰。 |
| `kind` | 字符串 | 枚举：`base`、`feature`、`resource`、`integration`。 |
| `hostApi` | 字符串 | 该插件编译所针对的宿主契约版本，语义化版本范围，如 `>=1.0.0 <2.0.0`。**可用的比较符与判定规则见 [docs/sdk/versioning.md](../sdk/versioning.md)**；`^`、`~`、`x`、`*` 不支持，写了会被拒。 |
| `artifact` | 对象 | 见第 5 节。 |
| `entry` | 字符串 | 入口类的全限定名。 |
| `requires` | 数组 | 见第 6 节。可以为空数组。 |
| `provides` | 数组 | 见第 7 节。可以为空数组。 |
| `permissions` | 数组 | 见第 8 节。可以为空数组。 |
| `degradable` | 数组 | 见第 9 节。可以为空数组。 |
| `pages` | 数组 | 可选。页面声明，见第 10 节。 |
| `themes` | 数组 | 可选。主题声明，见第 10 节。 |

不允许出现 `trustTier`、`signed`、`sha256`、`trusted` 等字段。信任档位由宿主判定，清单中出现这些字段直接拒绝。

## 4. `id`

- 格式：正则 `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$`，至少两段。
- 长度 3–64 个字符。
- 不允许以 `org.cordis`、`ev.host`、`ev.core` 开头。这些前缀保留给宿主和内核。
- 同一宿主内 `id` 唯一。发现阶段出现重复 `id`，两个都拒绝，不做"后者覆盖前者"。

## 5. `artifact`

```json
"artifact": { "format": "dex", "file": "classes.dex" }
```

- `format`：枚举 `dex`、`jar`。Android 端只允许 `dex`，桌面端只允许 `jar`。与宿主平台不匹配，拒绝。
- `file`：包内文件路径，相对包根，不允许 `..`，不允许绝对路径，必须存在于包内。
- 包内只允许一个 artifact。多余的 `.dex` 或 `.jar` 文件，拒绝安装。

## 6. `requires`

每一项：

```json
{ "interface": "ev.storage", "range": ">=1.0.0 <2.0.0", "optional": false }
```

- `interface`：接口 id，格式同第 4 节。
- `range`：语义化版本范围。
- `optional`：布尔，默认 `false`。
  - `false`：提供者不存在或版本不满足，插件拒绝加载。
  - `true`：缺失时插件照常加载，相关功能降级，降级项需在 `degradable` 中声明。
- 同一 `interface` 在 `requires` 中只能出现一次。

**kind 约束：**
- `resource` 类插件：`requires` 中的 `interface` 必须是宿主契约（`host-api`）定义的接口，不允许指向其他插件提供的服务。违反则拒绝。
- `base` 类插件：不允许 `requires` 中出现其他 `base` 类插件提供的接口，避免基础层内部循环。

## 7. `provides`

每一项：

```json
{ "service": "example.search", "version": "1.0.0" }
```

- `service`：服务 id，格式同第 4 节。
- `version`：严格语义化版本，表示该服务接口的版本。
- 同一插件内 `service` 唯一。
- 全局唯一性由宿主登记表检查：两个插件声明同一 `service` 时，后激活的拒绝，不做覆盖。

**实现一致性检查（运行时）：**
- 插件在 `apply` 中注册的服务集合，必须与 `provides` 完全一致。
- 注册了清单之外的服务，立即拒绝激活，并记入失败记录。
- 清单声明了但未注册的服务，视为激活失败。

## 8. `permissions`

- 枚举值只能来自宿主契约中定义的闭合集合，初版集合如下：
  `NETWORK`、`STORAGE_OWN`、`FILE_READ`、`FILE_WRITE`、`SHELL`、`CREDENTIAL_READ`。
- 出现未知值，拒绝安装。
- 同一个权限只能出现一次。
- 声明权限不等于获得权限。权限由宿主在裁决阶段统一申请、用户确认后才生效。运行时调用未获得的权限，返回拒绝。
- 高风险权限（`SHELL`、`CREDENTIAL_READ`、`FILE_WRITE`）在安装界面必须单独展示，不能合并在普通列表里。

## 9. `degradable`

- 每一项是一个字符串名字，格式 `^[a-z][a-z0-9_-]{0,31}$`。
- 名字只用于宿主记录状态和发出"已降级"信号，宿主不解释其含义。
- 同一插件内唯一。
- 名字由插件自定，宿主不校验它与哪个依赖或功能对应。宿主只记录该名字的开启或关闭状态。
- 插件负责：在可选依赖缺失时关闭对应功能，并通过宿主的降级信号接口上报该名字。
- 降级信号接口属于 `host-api`，定义在第一版契约中。

## 10. `pages` 与 `themes`

**pages**（可选）：

```json
{ "id": "example.home", "title": "首页", "entry": "example.ui.HomePage" }
```

- `id`：格式同第 4 节，同宿主内唯一。
- `entry`：实现页面的类全限定名，必须存在于 artifact 中。
- 页面只在登记表中登记 id 与入口，宿主不理解页面内容。

**themes**（可选）：

```json
{ "id": "example.dark", "title": "深色", "file": "themes/dark.json" }
```

- `file`：包内相对路径。宿主只保存 `插件 id + 主题 id` 的引用，不解析主题字段。
- 主题文件的内部结构由宿主界面层定义，不在本规范范围内。

## 11. 完整示例

```json
{
  "manifestVersion": 1,
  "id": "example.search",
  "name": "示例搜索",
  "version": "1.2.0",
  "kind": "feature",
  "hostApi": ">=1.0.0 <2.0.0",
  "artifact": { "format": "dex", "file": "classes.dex" },
  "entry": "example.search.Entry",
  "requires": [
    { "interface": "ev.storage", "range": ">=1.0.0 <2.0.0", "optional": false },
    { "interface": "ev.network", "range": ">=1.0.0 <2.0.0", "optional": true }
  ],
  "provides": [
    { "service": "example.search", "version": "1.0.0" }
  ],
  "permissions": ["NETWORK", "STORAGE_OWN"],
  "degradable": ["remote-results"],
  "pages": [
    { "id": "example.search.home", "title": "搜索", "entry": "example.search.SearchPage" }
  ],
  "themes": []
}
```

## 12. 校验顺序

宿主读取清单时按以下顺序校验，任一步失败即停止，不进入后续步骤，也不加载任何插件代码：

1. 包内是否恰好有一个 `ev-plugin.json`。
2. 大小是否 ≤ 64 KB，编码是否为 UTF-8 无 BOM。
3. JSON 语法是否合法，顶层是否只有本规范定义的字段。
4. `manifestVersion` 是否为 1。
5. 各字段类型、格式、枚举值是否合法。
6. 字段之间的约束：`requires` 与 `provides` 无自引用，`kind` 与 `requires` 的约束，`degradable` 只引用允许的名字。
7. `artifact.file` 是否存在于包内，且与宿主平台匹配。
8. `entry`、页面 `entry` 指向的类是否存在于 artifact 中（只检查存在，不加载）。
9. 与已安装插件的全局检查：`id`、`service`、`page id` 是否冲突。

## 13. 错误记录

每一次校验失败都要记录：插件包的 SHA-256、失败的步骤编号、字段路径（如 `requires[1].range`）、原因码。原因码是固定枚举，不用自由文本，方便统计和恢复页面展示。

## 14. 演进规则

- `manifestVersion` 只在不兼容的结构变化时递增。
- 新增可选字段：不升版本，但旧宿主遇到新字段要拒绝（严格模式），所以新增字段必须同时升 `manifestVersion`。
- 宿主同时支持的清单版本只保留当前版本和前一版本，超过窗口期的版本直接拒绝。
- 每次升版本，必须在本文件末尾追加变更记录，并附迁移说明。

## 15. 需要写进测试的用例

- 合法清单各 kind 各一份，全部通过。
- 每个必需字段缺失，各一份，全部拒绝，且原因码正确。
- 未知顶层字段、未知权限、未知 `kind`、`manifestVersion` 不为 1，各拒绝。
- `resource` 类依赖其他插件服务，拒绝。
- `id` 重复、`service` 重复，拒绝。
- 运行时注册了清单之外的服务，激活失败。
- `artifact.file` 指向 `..` 或绝对路径，拒绝。
- 包内有两个清单文件，拒绝。

## 16. 变更记录

- v1：初版定稿。
