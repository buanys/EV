plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.ui"
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
    // The design system is host-owned and shared with plugins through HostPackages, so every
    // Compose/Miuix coordinate is api: a plugin compiles against exactly what the host runs.
    api("androidx.compose.runtime:runtime:1.12.0")
    api("androidx.compose.ui:ui:1.12.0")
    api("androidx.compose.foundation:foundation:1.12.0")
    // Not api on purpose: plugins must not compile against an icon pack the host may not ship.
    // ui-kit re-exports the few glyphs it needs through EvIcons.
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    api("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    api("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
}

// SDK public API snapshot tasks (javap fallback; see gradle/api-snapshot.gradle.kts).
apply(from = rootProject.file("gradle/api-snapshot.gradle.kts"))
