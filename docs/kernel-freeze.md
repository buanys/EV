# 内核冻结章程

- 状态：**生效中**（2026-10-25 起，2026-11-07 修正作用域与基线）
- 决策依据：[ADR-0001](adr/0001-kernel-consumption.md)（D1 = A）
- 执行门禁：
  - [scripts/verify-kernel-lock.sh](../scripts/verify-kernel-lock.sh) —— 目录树哈希 == `KERNEL.lock`
  - [scripts/kernel-freeze.sh](../scripts/kernel-freeze.sh) —— 相对 vendor 提交没有白名单之外的改动
- 基线提交：**自动解析**为「把 `third_party/cordis` 加进来的那次提交」（不写死哈希，历史重写后仍然有效）
- 白名单：[gradle/kernel-freeze-allowlist.txt](../gradle/kernel-freeze-allowlist.txt)

## 一句话

内核是**依赖**，不是**源码**。写 EV 功能时，内核目录对你来说和 OkHttp 的 jar 没有区别：
只用它的 API，不打开它的源码。

## 冻结范围

真正 vendor 进仓库的只有两个模块，都在 `third_party/cordis/` 下：

| 模块 | 说明 |
|---|---|
| `third_party/cordis/core` | Cordis 运行时：Context、Fiber、Plugin、服务/事件/日志 |
| `third_party/cordis/loader` | 模块加载与依赖注入树、Android dex/APK 加载器 |

上游还有 `hmr`、`include`、`packages`、`utils`、`timer`、`logger-console`（以及 `packager`），
**EV 没有 vendor 它们**——所以它们也不在门禁扫描范围内。将来真要用，先 vendor 再扩范围，
不要只改文档。

> 2026-11-07 之前的门禁扫描的是 `loader core hmr include packages` 这五个**顶层**目录，
> 而这些目录在仓库里根本不存在（内核早就挪进 `third_party/cordis` 了）——门禁一直是空转的。
> 现在扫描 `third_party/cordis`，并且在 CI 里跑（见下）。

## 两条门禁，为什么要两条

| 门禁 | 拦住什么 |
|---|---|
| `verify-kernel-lock.sh` | 内核被就地改过（哈希与 `KERNEL.lock` 不符）。 |
| `kernel-freeze.sh` | 改了内核**同时**更新了 `KERNEL.lock`——哈希锁会通过，这一步会失败。 |

两条都在 `sh scripts/check.sh`（第一条）与 CI 的独立步骤（两者都有）里跑。
`scripts/check.sh` 是本地与 CI 共用的总入口，CI 里对应的步骤叫 `Architecture gates`。

## 三条规则

1. **默认不改。** 任何对冻结目录的改动，门禁直接判失败（白名单之外）。
2. **先绕后改。** 遇到"这功能内核不支持"，第一选择是在宿主层或插件层绕过去；
   绕不过去才考虑动内核——而且动内核走的是下面的例外流程，不是顺手提交。
3. **白名单不放语义。** 白名单只收"构建/测试接线"（如 `third_party/cordis/loader/build.gradle.kts`），
   运行时行为的改动永远不许进白名单。

## 例外流程（真的要动内核时）

1. 在提交信息里写明：为什么宿主层/插件层绕不过去；
2. 同步更新白名单，并在评审里说明理由；
3. 跑 `sh scripts/check.sh` 与 `bash scripts/kernel-freeze.sh`，确认只有登记的例外。

改完之后 `KERNEL.lock` 要不要更新：**只有内容确实变了才更新**，且必须与上游同步/PR 一起做；
门禁不允许"改了内核再刷新 lock"当作没事发生。
