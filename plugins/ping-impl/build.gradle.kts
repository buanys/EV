plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.plugins.ping.impl"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.ping.impl"
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
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":ui-kit"))
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
    // The contract lives in another plugin. Compiling against its class output (not a project
    // dependency) keeps it out of this container, so the class can only arrive at runtime through
    // the dependency loader.
    compileOnly(files(rootProject.file("plugins/ping-api/build/intermediates/aar_main_jar/debug/syncDebugLibJars/classes.jar")))
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/ping-impl-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("ping-impl.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce ping-impl.jar and ping-impl.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.ping.impl",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.ping.impl.PingImplPlugin",
              "apiVersion": 1,
              "dependencies": ["ev.buan.plugins.ping.api"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "ping-impl.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:ping-api:assembleDebug")
}
