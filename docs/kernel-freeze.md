# 内核冻结章程

- 状态：**生效中**（2026-10-25 起）
- 决策依据：[ADR-0001](adr/0001-kernel-consumption.md)（D1 = A）
- 执行门禁：[scripts/kernel-freeze.sh](../scripts/kernel-freeze.sh)
- 基线提交：`7b13e6b`
- 白名单：[gradle/kernel-freeze-allowlist.txt](../gradle/kernel-freeze-allowlist.txt)

## 一句话

内核是**依赖**，不是**源码**。写 EV 功能时，内核目录对你来说和 OkHttp 的 jar 没有区别：
只用它的 API，不打开它的源码。

## 冻结范围

| 模块 | 说明 |
|---|---|
| `core` | Cordis 运行时：Context、Fiber、Plugin、服务/事件/日志 |
| `loader` | 模块加载与依赖注入树、Android dex/APK 加载器 |
| `hmr` | 热替换服务 |
| `include` | 配置层叠/补丁解释器 |
| `packages` | `.kplugin` 清单与归档格式 |

`utils`、`timer`、`logger-console`、`packager` 同属内核阵营，按同样纪律对待。
（门禁当前只检查上表五个目录，需要时可扩。）

## 三条规则

1. **默认不改。** 任何对冻结目录的改动，门禁直接判失败（白名单之外）。
2. **先绕后改。** 遇到"这功能内核不支持"，第一选择是在宿主层或插件层绕过去；
   绕不过去才考虑动内核——而且动内核走的是下面的例外流程，不是顺手提交。
3. **白名单不放语义。** 白名单只收"构建/测试接线"（如 `loader/build.gradle.kts`），
   运行时行为的改动永远不许进白名单。

## 例外流程（真的要动内核时）

1. 在提交信息里写明：为什么宿主层/插件层绕不过去；
2. 同步更新基线或白名单，并在评审里说明理由；
3. 跑 `scripts/kernel-freeze.sh` 确认只有登记的例外。

## 什么时候解冻

- 手机端版本稳定，开始做**桌面端**（内核的 KMP 结构和 JVM 加载线就是为此留的）；
- 出现内核级的 bug 或安全漏洞，绕不过去。

解冻不是"删掉门禁"，是**带着门禁升级基线**：改动、评审、门禁变绿、更新基线提交号，
四步一个不能少。

## 怎么验证

```bash
scripts/kernel-freeze.sh
# 通过：内核相对 7b13e6b 没有白名单之外的改动。
```

CI 注意：浅克隆（`fetch-depth: 1`）下基线提交不存在，门禁会报错而非静默通过，
请用完整克隆。
