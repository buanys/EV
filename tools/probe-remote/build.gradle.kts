plugins {
    id("com.android.application")
}

/**
 * 只用来验证受限档子进程的夹具：不进默认构建，`-PevTestPlugins` 才 include。
 *
 * 它做两件事：往工具总线注册一个 `remote_ping`，以及**把自己的 pid 报回去**——
 * 有 pid 才能证明这个工具真的在 `:plugin` 进程里跑，而不是宿主进程里多了一段代码。
 */
android {
    namespace = "ev.buan.plugins.probe.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.probe.remote"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/probe-remote-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("probe-remote.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce probe-remote.jar / .json for EV/mods (restricted-tier fixture)"
    dependsOn(bundlePluginJar)
    doLast {
        File(pluginDist.get().asFile, "probe-remote.json").writeText(
            """
            {
              "id": "ev.buan.plugins.probe.remote",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.probe.remote.ProbeRemotePlugin",
              "apiVersion": 1,
              "dependencies": [],
              "permissions": [],
              "isolation": "separate-process"
            }
            """.trimIndent(),
        )
    }
}
