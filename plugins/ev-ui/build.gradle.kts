plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.plugins.eta.ui"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.eta.ui"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
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
// module, so this compiles against that module's class output: the contract itself stays out of this
// container and arrives at runtime through the dependency loader declared in the metadata.
val agentApiClasses = rootProject.file(
    "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
)

dependencies {
    compileOnly(project(":ui-kit"))
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    compileOnly(files(agentApiClasses))
    // System back for the in-plugin settings sheet: BackHandler comes from activity-compose, which
    // the host already ships (`androidx.activity` is a shared package). compileOnly like the rest of
    // the host surface, so no second copy is ever packaged into the container. The navigationevent
    // line pins what the host's own resolution ends up with - without it this classpath would ask
    // for the older version activity-compose declares and fail to resolve offline.
    compileOnly("androidx.activity:activity-compose:1.13.0")
    compileOnly("androidx.navigationevent:navigationevent-compose:1.1.2")

    // The conversation model, tool-step flattening (ChatConversation.kt) and the stored document
    // shape (ChatSessionDocuments.kt) are pure functions, so they are pinned on a plain JVM. The
    // tests need the agent contract, JUnit and the stdlib and nothing the host injects at runtime -
    // Compose or otherwise.
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(agentApiClasses))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    // The Compose compiler plugin is applied to every compilation of this module, test sources
    // included, and refuses to run without the runtime on the classpath. The tests themselves
    // render nothing; this only satisfies that check.
    testImplementation("androidx.compose.runtime:runtime:1.12.0")
    // The document codec test needs a working org.json (the plugin itself uses the platform copy,
    // which is never packaged), and the store test needs the storage contract plus the kernel types
    // its companion ServiceKey initialises with - both are compileOnly for the container.
    testImplementation("org.json:json:20260814")
    testImplementation(project(":plugin-api"))
    testImplementation(project(":loader"))
    // The memory panel's state machine calls the suspend MemoryService contract, so its test needs
    // runBlocking and nothing else from coroutines.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/eta-ui-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("eta-ui.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce eta-ui.jar and eta-ui.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        // The sidecar is the metadata a jar container is installed from: without the permission
        // array the host would load this plugin ungated and never lend it storage.
        val metadata = """
            {
              "id": "ev.buan.plugins.eta.ui",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.eta.ui.EtaUiPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.agent.api"],
              "permissions": ["PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "eta-ui.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// The agent-api class jar is produced by another project and only exists after its compile step, so
// a clean build needs this ordering edge; without it the two builds race.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
