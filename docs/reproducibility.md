# 可复现性：同一份源码，在另一台机器上还能不能构建出同样的东西

> 结论先说：**构建是"可重现"的，但不是"字节可复现"的**。同一 commit + 同一工具链能编出功能相同的
> APK；sha256 不会相同，因为 APK 签名带时间戳、zip 带文件时间。想知道自己编出来的包是不是真的
> 出自这份代码，看**签名证书摘要**，不要看文件哈希。
> 核实日期：2026-11-07。

## 一、仓库里锁住的东西

| 锁定项 | 在哪 | 说明 |
|---|---|---|
| Gradle 版本 | `gradle/wrapper/gradle-wrapper.properties` | `9.7.1`，带 `distributionSha256Sum`——分发包被替换会直接失败 |
| 依赖与插件版本 | `gradle/libs.versions.toml` | 唯一来源。27 个模块里重复写过的 junit / stdlib / json / coroutines / compose / miuix 全部改成别名；**同一族依赖不再允许出现两个版本** |
| 依赖校验和 | `gradle/verification-metadata.xml` | Gradle dependency verification：每个下载物的 sha256 都记着，换一个字节就失败 |
| 编译/构建版本 | `build.gradle.kts`（AGP 9.4.1、Kotlin 2.4.20）、`host-app/build.gradle.kts`（compileSdk 37 / minSdk 33 / targetSdk 37） | 与版本目录共用同一份版本号 |
| 内核 | `third_party/cordis` + `KERNEL.lock` | 目录树哈希锁；`scripts/verify-kernel-lock.sh` 校验 |

**内核是例外**：`third_party/cordis` 的构建脚本不引用我们的版本目录，它的测试依赖钉在 vendor 时的
版本（`kotlinx-coroutines-test:1.10.2`）。这不是偷懒——实测把它跟着目录升到 1.11.0，会让内核一个
时序用例失败（`service init blocks injection and returned disposer follows fiber`）。内核要动版本，
走 [kernel-freeze](kernel-freeze.md) 的例外流程。

## 二、机器上必须自己配的东西（不在仓库里）

| 是什么 | 为什么不在仓库 | 怎么配 |
|---|---|---|
| `keystore.properties` + `release-keystore.jks` | 私钥绝不能入库（`.gitignore` 已覆盖） | 见 [release-signing](release-signing.md)。CI 从 `KEYSTORE_BASE64` / `KEYSTORE_PROPERTIES` 两个 secret 还原 |
| `local.properties` 的 `sdk.dir` | 每台机器的 SDK 路径不同 | 指向本机 Android SDK（本机是 `/opt/android-sdk`） |
| `~/.gradle/gradle.properties` 里的 `android.aapt2FromMavenOverride` | aarch64 主机需要一个 qemu 转译过的 aapt2，属于"这台机器的二进制" | 本机：`/opt/tools/aapt2-9.4.1/aapt2`；x86_64 主机**不需要**这一项 |
| Gradle 可执行文件 | 本机把 9.7.1 放在 `/opt/gradle-9.7.1`，优先用它——`./gradlew` 要联网下载分发包，这台设备到 services.gradle.org 不通 | `GRADLE_BIN` 可覆盖；脚本顺序是 `GRADLE_BIN` → `/opt` → `./gradlew` |

## 三、复现一份构建

```bash
# 1. 工具链：JDK 21（temurin），Android SDK 带 platforms 37 / build-tools 37.0.0
sh scripts/check.sh              # 三条架构门禁 + 内核哈希锁
./gradlew assembleDebug          # 或本机 /opt/gradle-9.7.1/bin/gradle assembleDebug
./gradlew test                   # 全量单测（CI 里会跳过内核一个时序用例，见 build-on-device）

# 2. 发布包（需要 keystore.properties）
./gradlew :host-app:releaseGate :host-app:assembleRelease
./gradlew --no-configuration-cache :plugins:<类>:<名>:bundlePlugin   # 16 个插件
```

## 四、怎么确认"这个包确实来自这份代码"

```bash
apksigner verify --print-certs EV-v0.1.0.apk
# 证书 SHA-256 必须是 E2:60:17:EA:D7:41:B8:61:28:DD:49:C1:CC:86:1D:A3:4B:14:76:7C:C6:67:4D:F6:A5:35:21:9F:94:4F:87:04
```

这个摘要只取决于签名密钥，与构建机器、时间无关。发布页上的 APK 如果对不上，就不是我们签的。

## 五、已知的不可复现点（别假装它们不存在）

1. **字节不同**：APK 的 v2/v3 签名块含时间戳，zip 里也带文件时间；两次构建 sha256 不同，属正常。
   GitHub 上 release 资产的 `digest` 只能证明"这个文件没被换过"，不能拿来比对你的本地构建。
2. **Kotlin/Native 目标没锁**：仓库声明了 iosArm64 / iosSimulatorArm64 / macosArm64 / js 目标，
   但本机与 CI 都只跑 JVM/Android。谁要编 Apple 目标，得自己准备 Xcode 工具链（本机做不到）。
3. **aapt2 转译产物在机器上**：aarch64 用的 `/opt/tools/aapt2-9.4.1/aapt2` 是本地二进制，
   构建它所需的交叉编译步骤**没有进仓库**（待办：补一个生成脚本，或直接用 x86_64 构建机）。
4. **设备侧链路无法在 CI 复现**：root shell、无障碍读屏、真实 App 交互都只能上真机；
   这部分靠 [build-on-device](build-on-device.md) 里的人工验收清单。
5. **插件 jar 的 zip 时间戳**同样会让两次打包的 sha256 不同；sidecar 的 json 是确定的。

## 六、改了依赖之后要做什么

```bash
# 1. 改 gradle/libs.versions.toml（唯一来源）
# 2. 重新生成校验和（新增/升级依赖后必须）
./gradlew --write-verification-metadata sha256 assembleDebug test
# 3. 把 gradle/verification-metadata.xml 的 diff 一起提交，并在提交信息里写清楚升了什么
```

校验和文件**不要手改**、也不要在 CI 失败时删掉重生成来"让它过"——它拦的正是"依赖被换掉"这件事。
确实需要临时放行某个坐标时，用 `<trusted-artifacts>` 而不是关掉整个校验。

## 相关

- 门禁：[kernel-freeze](kernel-freeze.md) · [release-signing](release-signing.md)
- 构建机器：[build-on-device](build-on-device.md)
- 依赖与模块：[project-overview](project-overview.md)
