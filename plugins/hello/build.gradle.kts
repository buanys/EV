plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.cordis.plugins.hello"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.cordis.plugins.hello"
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

// The host owns the Cordis API, the Kotlin runtime and the host/plugin contract. Packaging any
// of them into the plugin would create a second class identity across the class-loader boundary.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":ui-kit"))
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
