plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.phone"
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

// 运行期的一切都来自宿主，打包进来只会制造第二份类身份。
// 单测跑在普通 JVM 上，是唯一的例外（需要真 stdlib 与真 org.json）。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    // Android 单测里 org.json 是 stub（方法体抛 not mocked），要用真实现。
    testImplementation("org.json:json:20260814")
    testImplementation(project(":host-api"))
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-phone-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-phone.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-phone.jar and agent-phone.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        File(pluginDist.get().asFile, "agent-phone.json").writeText(
            """
            {
              "id": "ev.buan.plugins.agent.phone",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.phone.AgentPhonePlugin",
              "apiVersion": 1,
              "dependencies": [],
              "permissions": ["CALENDAR", "ROOT_SHELL"]
            }
            """.trimIndent(),
        )
    }
}
