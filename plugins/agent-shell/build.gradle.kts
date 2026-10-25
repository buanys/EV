plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.shell"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.shell"
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

// The agent contract lives in another plugin. AGP refuses a project dependency on an application
// module, so this compiles against that module's class output instead: the contract stays out of this
// container and can only arrive at runtime through the dependency loader, as the sidecar declares.
val agentApiClasses = rootProject.file(
    "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    compileOnly(files(agentApiClasses))

    // Local unit tests run the tool logic against a fake shell: argument validation, timeout capping
    // and result encoding need the contract classes, the stdlib, coroutines (runBlocking) and org.json.
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(agentApiClasses))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.json:json:20260814")
}

/**
 * Ships this plugin as a jar container: the APK is only an intermediate that AGP knows how to
 * compile and dex. The published artifact is `classes*.dex` in a zip plus a metadata sidecar,
 * which is exactly what DexClassLoader accepts.
 */
val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-shell-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-shell.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-shell.jar and agent-shell.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        // The dependency on agent.tools guarantees the tool bus activates before this plugin and
        // outlives it on shutdown; ROOT_SHELL is declaration/grant/audit (see HostPermission).
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.shell",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.shell.AgentShellPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.api", "ev.buan.plugins.agent.tools"],
              "permissions": ["ROOT_SHELL"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "agent-shell.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// The agent-api class jar is produced by another project and only exists after its compile step, so
// a clean build needs this ordering edge; without it the two builds race.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
