# ADR-0003：权限范围（D3 = A）

- 状态：已采纳
- 日期：2026-10-24
- 决策编号：D3 = A
- 实施任务：[PLAN.md](../archive/2026-10-24-arch-hardening/PLAN.md) T05、T06、T07、T09
- 相关：[architecture.md](../architecture.md) 第 4 节第 7 项与第 7.1 节、[ADR-0002](0002-plugin-interface.md)

## 背景

EV 的插件是**同进程、同 UID** 的代码：`AndroidModuleLoader` 用 `DexClassLoader` 把插件 dex 装进
宿主进程，插件 Compose 代码直接在宿主 UI 树里执行（[architecture.md](../architecture.md) 第 2 节）。
宿主清单在基线 `9ad2569` 只声明了一个系统权限：

```xml
<!-- host/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

`plugin-api` 没有权限类型，宿主也没有任何 per-plugin 授权状态：装上即拥有宿主进程的全部能力。
在「插件可侧载」的前提下，用户看不到、也控制不了插件能做什么。

需要回答：EV 的权限模型要做到哪一步？是否承诺隔离不可信插件？

## 决策

**采用 D3 = A：权限 = 声明 + 用户授权 + API 级门控 + 审计。它是宿主服务的门控，不是 OS 沙箱；
不承诺隔离恶意插件。**

四段式含义：

1. **声明**：插件在元数据里声明所需权限（T05 起：APK 的 `cordis.permissions` 逗号分隔字符串；
   jar/dex 的 sidecar `"permissions": [...]`），宿主解析成 `HostPermission` 集合。未声明的能力不应被使用。
2. **用户授权**：宿主管理页展示「所需 / 已授予」，用户可授予或撤销；授权状态存宿主私有存储。
3. **API 级门控**：插件通过宿主提供的服务拿能力（T05–T09 起的 `PluginStorage`、`BackgroundService`、
   `PermissionService` 等）；未授权时宿主拒绝提供服务或抛 `SecurityException`，
   激活期校验还可以返回 `PERMISSION_DENIED`。
4. **审计**：宿主记录自己中介的调用（哪个插件、请求了什么、是否被拒）。

明确不做的：独立进程、Binder/IPC 边界、seccomp/SELinux 类 OS 级隔离、静态污点分析。
[PLAN.md](../archive/2026-10-24-arch-hardening/PLAN.md) §8 已把「不可信第三方插件沙箱」列为非目标。

## 代价与限制（必须写进对外文档的部分）

- **权限不阻止插件直接调用 Android API**。插件与宿主同 UID，插件代码可以自己 `File(...)` 读写宿主
  私有目录、自己反射调用系统 API；能否联网则取决于宿主清单是否声明 `INTERNET`（基线清单只声明
  `MANAGE_EXTERNAL_STORAGE`），插件无法单方面获得。宿主拦不住这些直接调用。所谓「每插件存储」只是
  **约定 + 宿主服务的路径约束 + API 门控**，不是文件系统隔离；插件绕开 `PluginStorage` 直接写
  `files/` 完全可行。
- **不承诺隔离恶意插件**。权限模型防的是「误用与无意越权 + 用户不可见」，不是「蓄意攻击」。
  侧载插件的信任前提仍然是「你信任你放进去的容器」。
- **崩溃不隔离**。同进程意味着插件异常可以杀掉宿主；Compose 没有错误边界，UI 异常直接崩进程。
  安全模式/崩溃兜底（T08）只能提供「下次启动全部禁用」的恢复路径，做不到进程内隔离。
- **审计有盲区**。宿主只能记录经过自己中介的调用；插件内部行为、直接系统调用、动态反射都不可见。
- **授权 UI 增加摩擦**。撤销权限会让插件激活失败（`PERMISSION_DENIED`）或功能降级，需要用户理解
  「宿主服务门控」与「系统权限」的区别；文档与文案必须避免暗示「已隔离」。
- **宿主侧系统权限仍是一个整体**。基线只有 `MANAGE_EXTERNAL_STORAGE`（用于扫描 `EV/mods`），
  它授予的是整个应用；插件间接共享这个能力，per-plugin 模型管不到它。后续若需要联网/通知，
  系统权限仍需在宿主清单声明（以合并后的清单为准），插件权限只是在其之上再加一层宿主门控。

## 备选方案（未采纳）

- **独立进程 / Binder 沙箱**：真正隔离插件代码与数据，但要求重做插件生命周期、IPC 契约、UI 渲染
  （Compose 跨进程不可行）、共享包边界与性能模型；属于另立项目，不在本轮。
- **只做安装期静态校验**（扫 manifest 声明、拒绝越权 API 引用）：没有运行时门控与用户可见的授权面，
  插件一旦被加载仍然全能力，无法撤销；比 A 更弱，不能满足「用户可授权/撤销」的目标。

## 影响

- 任何权限相关代码与文案都按「门控」表述，禁止使用「沙箱」「隔离」「安全容器」等词。
- 撤销授权必须产生可观察的失败路径（激活返回 `PERMISSION_DENIED` 或服务调用抛错），并在管理页可见。
- 如果将来要上真沙箱，本 ADR 需要被新 ADR 取代，并同时重写 [ADR-0002](0002-plugin-interface.md)
  的插件接口假设。
