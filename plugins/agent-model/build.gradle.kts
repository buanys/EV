plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.agent.model"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.agent.model"
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
// The unit-test runtime is exempt: it is never packaged, and the compiler-generated code needs the
// stdlib to run on the test JVM.
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

// The agent contracts ship as their own container. Compiling against that module's class output
// (rather than a project dependency) keeps its classes out of this container: at runtime they can
// only arrive through the dependency loader, which is what the sidecar `dependencies` asks for.
val agentApiClasses = rootProject.file(
    "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    compileOnly(files(agentApiClasses))
    // Declared per plugin on purpose: compileOnly keeps them out of the jar, the host supplies them.
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    // The provider code is pure JSON/selection/HTTP-interpretation logic; the serialization runtime
    // is a host-shared package, so it is compileOnly above and only the test JVM needs a real copy.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(files(agentApiClasses))
    // The contract interfaces initialize their companion ServiceKeys at class-init time and the
    // tests run suspend functions, so the JVM test classpath also needs the kernel types.
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/agent-model-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("agent-model.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce agent-model.jar and agent-model.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        // The sidecar is the metadata a jar container is installed from: without the dependency
        // and permission arrays the host would load this plugin unlinked and ungated.
        val metadata = """
            {
              "id": "ev.buan.plugins.agent.model",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.agent.model.AgentModelPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.api"],
              "permissions": ["NETWORK", "PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "agent-model.json").writeText(metadata)
    }
}

// The sidecar writer in this task (and the one gradle/plugin-verify.gradle.kts attaches) reaches the
// Project from a task action, so this task cannot be serialized into the configuration cache that
// gradle.properties enables globally. Declaring that explicitly makes the build disable the cache for
// this invocation instead of failing while storing the entry; the produced jar/json are unaffected.
tasks.named("bundlePlugin") {
    notCompatibleWithConfigurationCache("bundlePlugin writes its .json sidecar from a script closure that captures Project")
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// The agent-api class jar is produced by another project. Its path only exists after that project's
// compile step, so a clean build needs this ordering edge; without it the two builds race.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
