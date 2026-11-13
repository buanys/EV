package org.cordis.api

/**
 * 宿主 <-> 插件契约的**整数**版本，只为实验期容器保留。
 *
 * 新容器不要依赖它：manifest 里用 `hostApi` 声明语义化范围（如 `>=1.0.0 <2.0.0`），
 * 宿主按范围判定；没声明范围的旧容器才回落到 `apiVersion == PLUGIN_API_VERSION`。
 * 这两条门不叠加，规则见 `docs/sdk/versioning.md`。
 */
const val PLUGIN_API_VERSION: Int = 1

/**
 * 宿主当前实现的契约版本，`MAJOR.MINOR.PATCH`。
 *
 * 插件在 manifest 里用 `hostApi` 声明兼容范围，宿主用它做区间判定。
 *
 * 为什么是字符串而不是类型化常量：本模块是**插件编译期**依赖，必须能独立发布，
 * 不该为了一个版本点把宿主的判定逻辑一起带进来。解析与比较在宿主侧（`host-core`）。
 */
const val HOST_API_VERSION: String = "1.0.0"

/*
 * 这里曾经还有一个 `AgentSurface`（title + body + apiVersion）。
 *
 * 删掉的理由：它和 [PluginManifest] 的 name / description 是同一件事的两种写法，而且更弱——
 * manifest 带 id、能力清单、设置项、声明的权限，还会被宿主校验 `declared.id == 容器 id`；
 * AgentSurface 只有一个字符串标题和一段简介，宿主拿它只能填一张卡片。
 *
 * 两份声明并存时，插件作者必须在两个地方各写一遍同一段话，而宿主只在其中一处读。
 * 这不是"灵活"，是让第一次写插件的人必然写错一边。
 *
 * 现在只有一条声明路径：[ManifestProvider]。插件对宿主"说明自己是什么"的唯一入口。
 */
