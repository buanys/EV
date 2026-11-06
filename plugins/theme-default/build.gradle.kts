plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.plugins.theme"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.theme.default"
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
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))
}

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/theme-default-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("theme-default.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce theme-default.jar and theme-default.json for EV/mods"
    dependsOn(bundlePluginJar)
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.theme.default",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.theme.DefaultThemePlugin",
              "apiVersion": 1
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "theme-default.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
