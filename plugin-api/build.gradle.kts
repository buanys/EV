plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.cordis.api"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The kernel contract (org.cordis.Plugin / Context / ServiceKey) comes from the
    // host runtime; the API module only carries host <-> plugin business types.
    compileOnly(project(":core"))

    // Exposed as api so a plugin compiles against the exact Compose the host owns. Classes are
    // resolved from the host at runtime through HostPackages; plugins must not package them.
    api(project(":ui-kit"))
    api("androidx.compose.runtime:runtime:1.12.0")
    api("androidx.compose.ui:ui:1.12.0")
    api("androidx.compose.foundation:foundation:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
}

// SDK public API snapshot tasks (javap fallback; see gradle/api-snapshot.gradle.kts).
apply(from = rootProject.file("gradle/api-snapshot.gradle.kts"))
