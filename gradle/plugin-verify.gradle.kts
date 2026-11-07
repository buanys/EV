// Applied by every plugin module. Both plugin-wide `bundlePlugin` invariants live here, so a new
// plugin inherits them by applying this script instead of copying them into its build file:
//
// 1. bundlePlugin is not compatible with the configuration cache that gradle.properties enables
//    globally: the sidecar writer in each plugin's build script (and the jar check below) reaches
//    Project from a task action. This declaration is the single source of truth for every plugin -
//    do not repeat it in module build scripts. Gradle then discards the cache entry for the build
//    ("entry discarded") instead of failing while storing it; the produced jar/json are unaffected.
//    release-smoke.sh still passes --no-configuration-cache so the cache stays reusable for the
//    other (cache-compatible) tasks it runs.
tasks.matching { it.name == "bundlePlugin" }.configureEach {
    notCompatibleWithConfigurationCache("bundlePlugin writes sidecar/verifies jars from a script closure that captures Project")
}

// 2. Reject containers that reference the extended icon pack: the host ships icons-core only, so such
//    a reference is a guaranteed runtime NoClassDefFoundError.
tasks.matching { it.name == "bundlePlugin" }.configureEach {
    doLast {
        val dist = layout.buildDirectory.dir("dist").get().asFile
        val jars = dist.listFiles { f -> f.extension == "jar" }.orEmpty()
        check(jars.isNotEmpty()) { "bundlePlugin: ${dist.path} 下没有 jar" }

        // sidecar 的 entryClass 必须真的在 dex 里。
        // 改名时最容易被漏掉的就是这一处：构建照样成功、jar 也打得出来，
        // 但加载器找不到入口类，会退回用文件名当插件 id，表现为"永远停在待确认"。
        // 这个检查把那种失败从运行期提前到构建期。
        val sidecars = dist.listFiles { f -> f.extension == "json" }.orEmpty()
        check(sidecars.size == 1) {
            "bundlePlugin: 期望恰好一个 sidecar，实际 ${sidecars.size} 个（${sidecars.joinToString { it.name }}）"
        }
        val entryClass = Regex("\"entryClass\"\\s*:\\s*\"([^\"]+)\"")
            .find(sidecars[0].readText())
            ?.groupValues?.get(1)
        check(entryClass != null) { "bundlePlugin: ${sidecars[0].name} 里没有 entryClass" }
        val entryNeedle = "L" + entryClass.replace('.', '/') + ";"
        jars.forEach { jar ->
            java.util.zip.ZipFile(jar).use { zip ->
                val found = zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .any { entry ->
                        String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                            .contains(entryNeedle)
                    }
                check(found) {
                    "${jar.name} 里找不到 sidecar 声明的入口类 $entryClass。" +
                        "改类名之后别忘了同步 build.gradle.kts 里 sidecar 的 entryClass。"
                }
            }
        }

        // sidecar 的 version 与代码里 PluginManifest.version 是两份手写的事实，
        // 漂移过一次（agent-browse 1.2.0 vs 2.0.0）：依赖门禁读的是 sidecar，
        // 界面显示的是 manifest，两边不一致时"装的是哪个版本"没有唯一答案。
        val sidecarVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
            .find(sidecars[0].readText())?.groupValues?.get(1)
        val manifestVersion = project.fileTree("src/main/kotlin") { include("**/*.kt") }
            .files.asSequence().mapNotNull { source ->
                val text = source.readText()
                val at = text.indexOf("PluginManifest(")
                if (at < 0) null
                else Regex("version\\s*=\\s*\"([^\"]+)\"")
                    .find(text.substring(at))?.groupValues?.get(1)
            }.firstOrNull()
        if (sidecarVersion != null && manifestVersion != null) {
            check(sidecarVersion == manifestVersion) {
                "sidecar 版本 $sidecarVersion 与代码里 PluginManifest.version = $manifestVersion 不一致；两处必须相同"
            }
        }

        val needle = "androidx/compose/material/icons/extended"
        jars.forEach { jar ->
            java.util.zip.ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                    val text = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                    check(!text.contains(needle)) {
                        "${jar.name} 引用了 material-icons-extended（${entry.name}）。" +
                            "宿主只提供 icons-core，请改用 ev.buan.ui.EvIcons"
                    }
                }
            }
        }
    }
}
