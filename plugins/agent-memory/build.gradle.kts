plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.memory"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.memory"
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

    // The document codec test needs a working org.json (the plugin itself uses the platform copy,
    // which is never packaged) and the contract from the same class output the main source uses.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation(files(agentApiClasses))
    // The plugin container must not carry a stdlib (the host provides it), but the JVM test needs one.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-memory-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-memory.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-memory.jar and agent-memory.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.memory",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.memory.AgentMemoryPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.api"],
              "permissions": ["PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "agent-memory.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// The agent-api class jar is produced by another project and only exists after its compile step, so
// a clean build needs this ordering edge; without it the two builds race.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
