# 插件模板

复制这个目录就是写一个新插件的全部起点。它不参与构建——`settings.gradle.kts` 里没有它，
所以仓库照常编译，不会有第 10 个空插件混进产物。

## 四步改名

id 在四个地方各出现一次，**必须完全一致**，少改一处就会出现"装上了但找不到入口"。
其中 `entryClass` 忘了改也不要紧——`bundlePlugin` 会核对它是否真的在 dex 里，直接让构建失败。

| 位置 | 改成 |
|---|---|
| `build.gradle.kts` 的 `namespace` / `applicationId` | `ev.buan.plugins.<你的id>` |
| `build.gradle.kts` 的 `archiveFileName` / sidecar 的 `id` / `entryClass` | `<你的id>.jar` 等 |
| `src/main/AndroidManifest.xml` 的 `cordis.entry` | `ev.buan.plugins.<你的id>.<你的类>` |
| `TemplatePlugin` 的包名、类名、`PluginManifest.id` | 同上 |
| `settings.gradle.kts` | 加一行 `include(":plugins:<你的id>")` |

```bash
cp -r plugins/_template plugins/my-plugin
# 改完上面四处，然后
scripts/plugin.sh build my-plugin
```

如果用到 agent 契约（`AgentService` / `ToolRegistry` / `SessionLog` / `ApprovalPolicy`），
取消 `build.gradle.kts` 里 `agentApiClasses` 那段的注释，并在 dependencies 里加
`compileOnly(files(agentApiClasses))`、sidecar 里加 `"dependencies": ["ev.buan.plugins.agent.api"]`。
这两处必须成对：少写 sidecar 依赖，编译照样过，运行时抛 `ClassNotFoundException`。

## 构建与部署

```bash
# Linux 环境（chroot）：编译 + 打包
scripts/plugin.sh build my-plugin

# Android 环境（root）：复制、算 sha256、写信任、重启、抓加载日志
scripts/plugin.sh deploy my-plugin
```

`deploy` 会做完整条链，包括**重新信任**。原因是 EV 的信任绑定在 jar 的 sha256 上：
改一个字节，旧信任就失效，插件会停在 pending，所有依赖它的插件一起 `DEPENDENCY_MISSING`。
这一步是设计，不是噪音——但脚本会替你做，不需要手点。

## 常见失败与含义

| 现象 | 原因 |
|---|---|
| `plugin pending` | 未信任，或 jar 刚换过。跑 `deploy`，别手改 mods |
| `DEPENDENCY_MISSING` | sidecar 声明的依赖没装、没启用，或被依赖的那份自己 pending |
| `ClassNotFoundException` | sidecar 少了 `dependencies`，或入口类名与 `cordis.entry` 不一致 |
| `NoSuchMethodError` | 编译时对着旧 `plugin-api` 产物编译，设备上是新宿主。重新 build |
| 什么都没发生 | 忘了重启 EV 进程。插件只在启动时加载 |

## 三条纪律

1. **发布的必须撤销。** 每个 `publish` 都要在 `effect.collect` 里 `retract`，并用 `===`
   确认撤的是自己那一个。热重载会真的造出两个实例，漏了就留幽灵服务。
2. **跨插件共享的契约放 `:plugin-api`。** 模板里的 `PingService` 留在本地，是因为
   只有它自己用；一旦别的插件要调，它就得搬家。
3. **不要在插件里用 sidecar 没声明的能力。** 宿主不会给，失败发生在运行到那一行的时候。
