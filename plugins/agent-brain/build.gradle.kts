plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.brain"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.brain"
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

    // Orchestration tests use fake ModelService/ToolRegistry/MemoryService implementations on a plain
    // JVM: they need the contract classes, the stdlib and coroutines (for runBlocking), nothing else.
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(agentApiClasses))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // The contract interfaces initialize their companion ServiceKeys at class-init time, so the JVM
    // test classpath also needs the kernel types the plugin normally receives from the host.
    testImplementation(project(":loader"))
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-brain-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-brain.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-brain.jar and agent-brain.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        // No permissions: the brain only composes capabilities other plugins publish; the model
        // plugin owns NETWORK, the tools/memory plugins own PLUGIN_STORAGE.
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.brain",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.brain.AgentBrainPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.api"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "agent-brain.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// The agent-api class jar is produced by another project and only exists after its compile step, so
// a clean build needs this ordering edge; without it the two builds race.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
