plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.browse"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.browse"
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

val agentApiClasses = rootProject.file(
    "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    compileOnly(files(agentApiClasses))
    // 宿主已打包 okhttp3 与 jsoup（gradle/shared-packages.txt）；这里只借用编译期签名。
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    compileOnly("org.jsoup:jsoup:1.23.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation(files(agentApiClasses))
    testImplementation("org.jsoup:jsoup:1.23.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-browse-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-browse.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-browse.jar and agent-browse.json for EV/mods"
    dependsOn(bundlePluginJar)
    val distDir = pluginDist
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.browse",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.browse.AgentBrowsePlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.tools"]
            }
        """.trimIndent()
        File(distDir.get().asFile, "agent-browse.json").writeText(metadata)
    }
}
