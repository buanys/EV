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
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    // Not api on purpose: plugins must not compile against an icon pack the host may not ship.
    // ui-kit re-exports the few glyphs it needs through EvIcons.
    // COUI 一次性引入：它带来模糊、材质、轮廓光这些效果，Miuix 逐步退场。
    api(libs.coui)
    // 图标集：COUI 自带的一套，替换掉 material-icons-core。
    api(libs.coui.icons)
    api(libs.miuix.ui)
    api(libs.miuix.icons)

    // Blur / glass effects. Backdrop blur + liquid-glass refraction need AGSL (Android 33+),
    // which the device runs; the library falls back on its own for older API levels.
    implementation("com.github.skydoves:cloudy:1.0.0-alpha01")

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
}

// SDK public API snapshot tasks (javap fallback; see gradle/api-snapshot.gradle.kts).
apply(from = rootProject.file("gradle/api-snapshot.gradle.kts"))
