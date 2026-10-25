# 发布签名（D12）

## 现状

| 变体 | 签名 | 说明 |
|---|---|---|
| debug | SDK 自动生成的 debug key | 开发用，插件信任锚定同一把 key |
| release | 必须由 `keystore.properties` 指定的发布 key | 缺失时 `:host:releaseGate` 直接失败（见下节）；debug key 只保留为显式旁路 |

## 门禁：`:host:releaseGate` 要求发布密钥

`:host:releaseGate` = `verifyReleaseSigning` + `verifySharedPackagesInDex` + `verifyForbiddenPackages`。
其中 `verifyReleaseSigning`（[host/build.gradle.kts](../host/build.gradle.kts)，
任务类 [VerifyReleaseSigningTask.java](../buildSrc/src/main/java/ev/buan/gradle/VerifyReleaseSigningTask.java)）
检查三件事：

1. 仓库根存在 `keystore.properties`；
2. 四个字段齐全且非空：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`；
3. `storeFile`（相对仓库根解析）指向的文件存在。

失败时直接打印修复步骤（复制模板、`keytool` 生成命令、本地旁路命令），不需要再去翻代码。
门禁只读两个文件，`assembleRelease` 被 `mustRunAfter` 排在它后面：没有密钥时几秒内失败，
不会先跑完一遍 R8 才报错。

### 本地旁路（只用于侧载冒烟）

```powershell
.\gradlew.bat :host:releaseGate -PallowDebugSigning=true
```

这个 flag **不改变签名结果**：没有 `keystore.properties` 时 release 仍用 debug key，flag 只让门禁
放行并打印一条警告。产物只能侧载，不能发布。

CI（[.github/workflows/android.yml](../.github/workflows/android.yml)）没有、也不应该持有发布密钥，
因此 releaseGate 这一步显式带 `-PallowDebugSigning=true`：CI 验证的是构建与门禁逻辑，不产出可发布包。

`scripts/release-smoke.sh` 走 `:host:releaseGate`，并固定带 `-PallowDebugSigning=true`（T23）：
没有 `keystore.properties` 的机器也能跑完冒烟，产物是 debug 签名、仅可侧载；需要验证真实发布密钥时，
再配好 `keystore.properties` 重跑。

`:host:assembleRelease` 本身不跑 `verifyReleaseSigning`，以便在没有密钥的机器上仍能产出可侧载的包。
**发布流程一律走 `:host:releaseGate`**，不要直接拿 `assembleRelease` 的产物去发布。

## 配置读取与 configuration cache

`host/build.gradle.kts` 通过 `providers.fileContents(...)` 读取 `keystore.properties`，这是
configuration cache 追踪的输入：新建或修改该文件会让缓存的配置失效，不会出现「门禁通过、但构建仍是
上一轮 debug 签名」的过期状态。签名配置仍按「有 properties 用 release key，否则用 debug key」构建。

## 材料不入库

仓库根 `.gitignore` 已忽略 `keystore.properties`、`*.jks`、`*.keystore`。
需要时复制 [keystore.properties.example](../keystore.properties.example) 为 `keystore.properties` 并填值：

```properties
storeFile=release-keystore.jks
storePassword=...
keyAlias=ev-release
keyPassword=...
```

`storeFile` 相对仓库根目录解析。生成 key（在仓库外执行，别把 jks 放进仓库）：

```bash
keytool -genkeypair -v -keystore ~/ev-release.jks -alias ev-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=EV, O=EV, C=CN"
```

## 换 key 的影响（重要）

插件信任**锚定宿主证书**：`HostPolicy.hostCertificateSha256` 来自当前安装包的签名
（[PluginTrust.kt](../host/src/main/kotlin/ev/buan/PluginTrust.kt)），判定在
[PluginValidator.kt](../host-core/src/main/kotlin/ev/buan/hostcore/validation/PluginValidator.kt)。

因此换 key 时：

1. 所有**已签名 APK 插件**必须用新 key 重签，否则一律 `SIGNATURE_HOST_MISMATCH`。
2. **未签名 jar/dex** 不受影响；它们的放行依据是用户在管理页批准的 (id, sha256)，与证书无关。
3. 已装旧 key 版本无法直接覆盖升级（签名不同），需先卸载再装——卸载会清掉应用私有目录，插件需重新放回 `EV/mods` 并重新确认。
4. 换 key 后 `EV/mods` 里旧 key 签的 APK 会全部被拒；这是设计行为，不是 bug。

## 为什么不把 keystore 放仓库

插件信任等价于“宿主签名者的信任”。keystore 泄漏等于任何人可以签发宿主同签名的插件。
本仓库只保存**读取方式**，不保存材料；CI 也只能通过显式的 `-PallowDebugSigning=true` 走 debug key。
