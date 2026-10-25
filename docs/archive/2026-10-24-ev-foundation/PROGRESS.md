# EV 地基加固 — 进度台账

分支 build/gradle9-agp9 · 起点 HEAD 未提交（host/plugin-api/ui-kit/plugins 均 untracked）

## 任务清单（17 项，A→F）
- [x] A1 ui-kit 图标改为 core + EvIcons 封装
- [x] A2 :plugins:chat 编译通过，chat.jar 38.6KB
- [x] A3 19 项全部 keep+有类；修 okhttp3 包名（0→430 类）；补 androidx.sqlite
- [x] B4 gradle/shared-packages.txt 为唯一来源
- [x] B5 plugin-api 单测 4 项 + :host:verifySharedPackagesInDex（dex 类数>0）
- [x] B6 plugins/coldapi（OkHttp/Jsoup/coroutines/Canvas）
- [x] B7 verifyForbiddenPackages + 插件 extended 图标引用拦截
- [x] C8 PluginValidator（纯 Kotlin，plugin-api，无 Context）
- [x] C9 UnsignedApprovalStore + 管理页确认（绑定 id+sha256）
- [x] C 收尾：AndroidModuleVerifier(id, sha256) + verifyAtLoad 二道复核；allowUnsigned 已删
- [x] C10 错误码枚举 + 14 个单测（每关一过一败）
- [ ] D11 HostRuntime 提升 Application 级，去 EvHost
- [ ] D12 正式签名方案文档（不入库 keystore）
- [ ] D13 两棵 composition 策略文档
- [ ] E14 loader android 测试恢复或说明原因
- [ ] E15 host/plugin-api/ui-kit 最小测试
- [ ] E16 release 冒烟脚本
- [ ] F17 README

## 已知基线（盘点结论）
- release 41.3MB，-dontobfuscate，keep 仅覆盖 kotlin/coroutines/serialization/compose/activity/lifecycle/annotation/miuix/ev.buan.ui/org.cordis
- 未覆盖：okhttp okio room datastore navigationevent jsoup markdown compress xz dexkit hiddenapibypass libxposed
- 闸门分散：PluginInstaller.accept(127/133/138) + HostRuntime:140 超时 + HostRuntime:87 开关
- EvHost 可变单例 PluginPageActivity:72-74

## A 组结果（已验）
- release 41.3MB → 27.5MB（ui-kit 不再 api 传递 icons-extended）
- chat: debug APK 0.1MB + bundlePlugin 产出 chat.jar/chat.json
- 禁忌包定义仍为 0；19 个共享包 keep 全覆盖
- R8 曾因 commons-compress 可选编解码(zstd/brotli/asm)缺类报错 → 加 -dontwarn
- 发现：loader 的 loadClass 兜底是 platformClassLoader（:295），共享清单写错即插件拿不到

## B 组结果（已验）
- 单一来源 gradle/shared-packages.txt（19 项）；HostPackages.kt 必须与之一致
- plugin-api 单测 4 项通过（注意：:host 是 application，AGP9 下没有 testDebugUnitTest 任务）
- releaseGate = verifySharedPackagesInDex + verifyForbiddenPackages，均通过
- coldapi.jar 29.7KB，295 个类定义，除 AGP synthetic android.* 外无外部类
- 插件 extended 图标引用拦截已加（plugin-verify.gradle.kts），逻辑用合成样本验证会触发

## C 组结果（已验）
- PluginValidator: 5 个稳定错误码，输入全为纯数据（PluginCandidate/HostPolicy）
- 单测 14 项通过（每关至少一过一败 + 顺序断言）
- UnsignedApprovalStore 存 ev.unsigned（私有 SharedPreferences），绑定 id->sha256
- 管理页新增"待确认的未签名插件"区，点信任记录哈希，重启生效
- release 构建通过

## 提交
- c1c73cc A-C
- 219838b D
- 435f74d E
- a1e81cb F

## 当前切片
全部完成，等真机结果（不再自行继续）
