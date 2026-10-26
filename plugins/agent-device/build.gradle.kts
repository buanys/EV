plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.device"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.device"
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

// 宿主在运行时提供这些包；打进容器会造成类身份重复。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// 工具契约在另一个插件（AGP 不允许对 application 模块建项目依赖），编译期用它的类输出，
// 运行时通过依赖加载器到达——与 agent-tools / agent-memory 同一套做法。
val agentApiClasses = rootProject.file(
    "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    compileOnly(files(agentApiClasses))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation(files(agentApiClasses))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-device-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-device.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-device.jar and agent-device.json for EV/mods"
    dependsOn(bundlePluginJar)
    // 配置期捕获 Provider：doLast 里直接引用 pluginDist 会被配置缓存判为"序列化脚本对象"。
    val distDir = pluginDist
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.device",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.device.AgentDevicePlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.tools"]
            }
        """.trimIndent()
        File(distDir.get().asFile, "agent-device.json").writeText(metadata)
    }
}
