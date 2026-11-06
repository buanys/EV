plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.forge"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.forge"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// forge 的编译契约（forge-compiler）仍然装在另一个插件里：AGP 不允许对 application 模块建
// 项目依赖，只能对着它的编译产物编译，运行时由依赖加载器按 sidecar 的 dependencies 送达。
// agent 契约已经并进 :host-api，这里只剩这一个后门——它属于 Phase 1 的遗留，之后再处理。
val forgeCompilerClasses = rootProject.file(
    "plugins/forge-compiler/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)
dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))
    compileOnly(files(forgeCompilerClasses))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation("org.json:json:20260814")
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/forge-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("forge.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce forge.jar and forge.json for EV/mods"
    dependsOn(bundlePluginJar)
    val distDir = pluginDist
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.forge",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.forge.AgentForgePlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.tools", "ev.buan.plugins.forge.compiler"]
            }
        """.trimIndent()
        File(distDir.get().asFile, "forge.json").writeText(metadata)
    }
}

// 两份前置类输出都来自别的项目，干净树上要先把它们编出来，否则两个构建会抢跑。

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
