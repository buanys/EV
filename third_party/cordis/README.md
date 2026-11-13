# 内核（vendor）

这里是上游 [meteor149/cordis-kotlin](https://github.com/meteor149/cordis-kotlin) 的 `core/` 与 `loader/`，
**原样 vendor 进本仓库并冻结**。版本、上游提交与目录树哈希记在 [KERNEL.lock](KERNEL.lock)。

## 为什么放在这里而不是当依赖

`core` 和 `loader` 是要被"改"的东西，不是要被"用"的东西——但改动必须发生在**上游**。
vendor 进来自带两个好处：构建不依赖某个 SNAPSHOT 的可用性；每次同步都是一个可 diff 的提交。

## 能做什么、不能做什么

- **不能**在 `third_party/cordis/` 里直接改代码。改了 `sh scripts/verify-kernel-lock.sh` 会失败。
- 需要的内核能力，先在上游分支实现，合并后同步回来，更新 `KERNEL.lock` 的
  `upstream_commit` 与 `tree_sha256`，并经过人工审核。
- 宿主需要的功能如果内核做不到，就在宿主里补（见 `docs/newdocs/最高规则` §三.6）；
  **不为了让宿主好写而改内核**。

## 校验

```sh
sh scripts/verify-kernel-lock.sh
```

它只做一件事：重算目录树哈希，和 `KERNEL.lock` 里记的比。哈希算法写死在锁文件里，
算法本身改了就等于门禁改了。

## 两个约定

1. **Gradle 项目名仍是 `:core` / `:loader`**，只有目录搬到了这里（`settings.gradle.kts` 里
   用 `projectDir` 指过来）。这样几十处 `project(":core")` 不用跟着改，目录归目录、坐标归坐标。
2. **只引入了 `core` 和 `loader`**。`hmr`、`include`、`packages`、`packager` 里只有
   `packages`/`packager` 还在构建内（打 mods 要用），其余已归档到
   `/workspace/EV-archive/2026-11-05/`。要引回任何一个，先在这里写清理由。
