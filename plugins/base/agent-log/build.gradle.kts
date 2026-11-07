plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.log"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.log"
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

// 宿主在运行时提供这些包（shared-packages.txt）；打进容器会造成类身份重复。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// 会话事件流的契约在 agent-api 里。AGP 不允许一个 application 模块依赖另一个 application
// 模块，所以对着它的编译产物编译；契约不会被打进这个容器，只可能在运行时由依赖加载器送进来。
dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))

    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":host-api"))
    // 单测要在普通 JVM 上解析 PluginStorage 与 ServiceKey。
    testImplementation(project(":host-api"))
    testImplementation(project(":loader"))
    // 会话日志的读写是挂起函数，单测用 runBlocking 跑它们。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // 插件本体用平台自带的 org.json（永不打包），JVM 单测需要真实现。
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-log-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-log.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-log.jar and agent-log.json for EV/mods"
    dependsOn(bundlePluginJar)
    // 在配置期把 Provider 捕获成局部变量：doLast 里直接引用 pluginDist 会被配置缓存
    // 判定为"序列化 Gradle 脚本对象"，Gradle 9 会直接让构建失败。
    val distDir = pluginDist
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.log",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.log.AgentLogPlugin",
              "apiVersion": 1,
              "dependencies": [],
              "permissions": ["PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(distDir.get().asFile, "agent-log.json").writeText(metadata)
    }
}


apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
