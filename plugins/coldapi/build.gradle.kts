plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.plugins.coldapi"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.coldapi"
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
    // Declared per plugin on purpose: compileOnly keeps them out of the jar, the host supplies them.
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    compileOnly("org.jsoup:jsoup:1.23.2")
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
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/coldapi-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("coldapi.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce coldapi.jar and coldapi.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.coldapi",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.coldapi.ColdApiPlugin",
              "apiVersion": 1
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "coldapi.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
