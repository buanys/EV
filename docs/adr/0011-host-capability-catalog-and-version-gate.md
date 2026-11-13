# ADR-0011：宿主能力面归档 + `hostApi` 范围门

- 状态：已采纳
- 日期：2026-11-10
- 相关：[ADR-0002 插件接口](0002-plugin-interface.md)、[ADR-0003 权限范围](0003-permission-scope.md)、
  [docs/sdk/host-services.md](../sdk/host-services.md)、[docs/sdk/versioning.md](../sdk/versioning.md)

## 背景

准备重写全部插件之前，先做了一次宿主体检，查出三件事：

1. **宿主偏科：权限齐、服务缺。**
   `host-app/AndroidManifest.xml` 声明了 40 条 Android 运行时权限，
   `host-api` 只兑现了 35 个服务 Key，其中真正借给插件的宿主实现只有 17 个。
   通讯录、短信、媒体库、位置、定时这五类能力**一条服务都没有**，
   而它们背后的 Android 权限早就在 manifest 里躺着。所以覆盖面清单里 5 条 ❌ 不是插件写不出来。

2. **权限说明与实际实现不符。**
   `HostPermission` 的 KDoc 声称 `NOTIFICATIONS` / `CLIPBOARD` / `INSTALL_PACKAGES`
   是"没有服务的保留词"；实际上 `DeviceContextService` 已经提供 `notify()`、`clipboard()`、
   `setClipboard()`，**而且不设任何门**。照 KDoc 写插件的人会得出错误结论。

3. **规范与实现漂移。**
   `ev-manifest-spec.md` 规定插件用 `hostApi: ">=1.0.0 <2.0.0"` 声明兼容范围，
   但**全仓没有任何代码读这个字段**。真实生效的是 `candidate.apiVersion == policy.supportedApiVersion`
   这个整数等值判断。写了范围的插件等于白写。

## 决策

### D1：`docs/sdk/` 作为宿主能力面的唯一权威来源

新增 `docs/sdk/`，三份文件 + 一份机器可读清单：

- `host-services.md`：35 个 Key × 四种送达方式 × 门控权限 × 缺失时行为 × 缺口清单
- `catalog.json`：同一份事实的机器可读形式
- `versioning.md`：版本门规则
- `README.md`：这个文件夹为什么存在

**判定依据是代码，不是设计意图。** 与 `HostPermission` 的 KDoc 冲突时以本文档为准，并顺手修 KDoc。

把 AI 自己写插件当作目标读者之一，所以同一份事实出两种格式：人读 md，机器读 json。
将来接生成/校验流水线时，不需要再解析自然语言。

### D2：`hostApi` 范围门取代整数等值门

- 声明了 `hostApi` → 走范围判定（`HostApiRange`），解析失败报 `API_RANGE_INVALID`
- 没声明 → 回落整数门（旧容器兼容）

**两条门不叠加**，一次只走一条。理由：叠加后"到底哪个门拒了我"要靠读源码才能回答。

范围语法只支持 `>= > <= < =` 加精确版本，**故意不支持** `^`、`~`、`x`、`*`、`||`。
一个把 `^1.0.0` 误当成 `>=1.0.0` 的解析器，会让作者以为上界生效了；明确拒绝至少让人当场知道要改。

宿主版本以字符串常量 `HOST_API_VERSION = "1.0.0"` 落在 `host-api`，
解析在宿主侧（`host-core`）——**不为一个版本点把判定逻辑塞进插件编译期依赖**。

### D3：发布坐标的现状如实记录，不推广白名单

实测 `publishToMavenLocal` **当前跑不通**：vanniktech 插件为 publication 生成 javadoc 时
拉入 dokka，连带 34 个 artifact 不在 `gradle/verification-metadata.xml` 里，被依赖校验门拦下。
即"发布配置存在，但从未在可复现性门禁下跑通过"。

**决定：不为发布放行这批 artifact。** 独立插件工程改用 Gradle 复合构建
（`includeBuild` + 依赖替换），已实测可编译。理由：javadoc jar 对插件毫无用处，
为它扩大受信依赖面是拿安全边界换一个没人用的产物。真要发布时再单独决策。

## 代价与限制

- **`catalog.json` 与 `host-services.md` 会不同步。** 两份同源事实，改一处要改两处。
  缓解办法是维护规则写进文档，但没有机器校验。等真正接 AI 生成时，应当让
  `catalog.json` 成为唯一数据源、md 由它渲染，那一天再消灭重复。
- **范围门只有区间，没有"exactly this build"。** 宿主如果是 nightly 或带补丁，
  范围判定看不出差异。当前版本号由人工维护，不来自 git 描述。
- **复合构建要求插件工程能访问 EV 源码树。** 对"同一个人的机器"够用，
  对"发给别人"不够——那时还是得回到发布坐标，也就是必须面对 D3 里那 34 个 artifact。
- **整数门没有删除。** 它会让"新容器"有两种合法形态，长期是负担。
  等实验期容器全部退役，应当删掉整数门并提升 `HOST_API_VERSION` 的 major。
