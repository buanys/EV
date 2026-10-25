plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.plugins.ping.api"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.ping.api"
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
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/ping-api-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("ping-api.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce ping-api.jar and ping-api.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.ping.api",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.ping.api.PingApiPlugin",
              "apiVersion": 1
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "ping-api.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
