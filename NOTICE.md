# 来源与第三方许可

## cordis-kotlin（上游）

本项目的内核部分派生自 **[meteor149/cordis-kotlin](https://github.com/meteor149/cordis-kotlin)**。
派生范围包括受内核冻结门禁保护的目录（`loader/`、`core/`、`hmr/`、`include/`、`packages/`）、
打包与发布接线（`packager/`）、插件契约与设计系统（`plugin-api/`、`ui-kit/`），以及随它们一起
继承的构建脚本、`buildSrc/` 工具类和 `gradle/` 配置。

上游以 MIT 许可证发布，版权声明为：

```
MIT License

Copyright (c) 2021-present Shigma
```

许可证全文见 [LICENSE](LICENSE)。该文件原样保留上游版权声明与许可条款，未做删改；
本项目修改与新增的部分版权归 buanys，已在该文件第二行注明。

MIT 的义务是保留上述版权声明和许可声明（适用于软件的所有副本或实质部分）——本仓库的
[LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md) 共同满足这一点。若你分发本项目的修改版本，
请一并保留这两个文件。

## 本仓库新增的部分

在上述派生之外新增：Android 宿主（`:host`、`:host-core`）、内置插件（`plugins/`）、
agent 层实现、设备侧构建与验证脚本。这些部分同样以 MIT 发布。

## 第三方依赖

通过 Gradle 声明、按 Maven 坐标引入的第三方库（AndroidX、Kotlin、OkHttp、kotlinx、
Miuix、libxposed、dexkit、jsoup 等）各自遵循其原始许可证，本文件不逐条复制其全文；
完整依赖清单见各模块的 `build.gradle.kts`。
