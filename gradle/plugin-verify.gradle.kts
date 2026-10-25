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
