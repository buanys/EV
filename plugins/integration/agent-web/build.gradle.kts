plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.web"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.web"
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

// Everything here comes from the host at runtime; packaging it would duplicate class identities.
// The unit test runtime is the exception: it runs on a plain JVM and needs the real stdlib.
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// The agent contract lives in another plugin; see agent-tools' build script for why this compiles
// against that module's class output instead of a project dependency.
dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation(project(":host-api"))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-web-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-web.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-web.jar and agent-web.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.web",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.web.AgentWebPlugin",
              "apiVersion": 1,
              "dependencies": [],
              "permissions": ["NETWORK", "PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "agent-web.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

