import java.util.Properties

import ev.buan.gradle.VerifyForbiddenPackagesTask
import ev.buan.gradle.VerifyReleaseSigningTask
import ev.buan.gradle.VerifySharedPackagesInDexTask
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives outside the repository: `keystore.properties` is git-ignored and required
// for a release build - `:host:releaseGate` fails without it. A debug-signed release is still
// possible for side-loading smoke runs, but only explicitly, per invocation:
// `-PallowDebugSigning=true`. See docs/release-signing.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")

// `fileContents` is a configuration-cache-tracked read, unlike the plain `isFile` check used before:
// creating or editing keystore.properties invalidates the cached configuration instead of leaving a
// stale (debug-signed) signing config behind for the next release build.
val keystorePropertiesText = providers.fileContents(
    rootProject.layout.projectDirectory.file("keystore.properties"),
).asText.getOrNull()

val keystoreProperties = keystorePropertiesText?.let { text ->
    Properties().apply { load(text.reader()) }
}

// The single, explicit opt-out from the release key gate. It does not change what gets signed: with
// no keystore.properties the release APK still uses the debug key - the flag only lets the gate pass.
val allowDebugSigningProperty = providers.gradleProperty("allowDebugSigning")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

android {
    namespace = "ev.buan"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 must be exercised before the contract grows: obfuscation is what proves the
            // keep rules are complete.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds are signed with the repository-external release key. The debug fallback
            // exists only so a side-loadable artifact can be built at all without secrets; passing
            // `:host:releaseGate` through it requires the explicit `-PallowDebugSigning=true`
            // opt-out (see verifyReleaseSigning below and docs/release-signing.md).
            signingConfig = if (keystoreProperties != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // AGP 9 rejects Provider-backed source dirs.
    // generated/plugin-assets/debug 预留给内置 seed 插件的 assets 目录（当前无内置插件，目录可为空）。
    sourceSets.getByName("debug").assets.srcDir(
        layout.buildDirectory.dir("generated/plugin-assets/debug").get().asFile,
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":loader"))
    implementation(project(":ui-kit"))
    implementation(project(":plugin-api"))
    // Host-only logic that must not be published as part of the plugin contract (see :host-core).
    implementation(project(":host-core"))

    // Shared runtime the host lends to every plugin. Plugins compileOnly these coordinates and
    // must not package them, or the class identity would split across the class-loader boundary.
    // Versions follow Eta's catalog so a migrated plugin needs no second copy.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.runtime:runtime:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.jetbrains:markdown:0.7.16")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // org.json must come from the platform: bundling it shadows core-libart and R8 inlining
    // then references fields the platform class does not have.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
}

// 内置 seed 插件已移除（hello 是实验插件，2026-10-25 摘除构建）。
// 宿主运行时的 assets/plugins/android 扫描机制（PluginInstaller.collectBundled）保留：
// 以后需要内置插件时，往该 assets 目录放 APK 即可，无需改运行时代码。

// ---------------------------------------------------------------------------
// Release gates. These fail the build, not the app: a shared package that silently stopped being
// shipped, or a platform class we accidentally re-bundled, must never reach a device again.
//
// The checks are task classes in buildSrc (VerifySharedPackagesInDexTask /
// VerifyForbiddenPackagesTask), not `doLast { }` blocks: a closure here captures the script object,
// which the configuration cache refuses to serialize. The task classes declare their inputs, so the
// same build works with `org.gradle.configuration-cache=true` and no extra flag.
// ---------------------------------------------------------------------------

val sharedPackagesSourceFile = rootProject.file("gradle/shared-packages.txt")

// assembleRelease writes this; the gates match the original (unobfuscated) class names in it.
val releaseR8Mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

val forbiddenPackageNames = listOf(
    "org.json", "org.apache.http", "org.xmlpull", "org.w3c.dom", "org.xml.sax", "javax.xml",
    "junit",
    // 宿主只发 icons-core；把 extended 打进来等于给插件一个它拿不到的包
    "androidx.compose.material.icons.extended",
)

val verifySharedPackagesInDex = tasks.register<VerifySharedPackagesInDexTask>("verifySharedPackagesInDex") {
    group = "verification"
    description = "每个共享包在 release dex 中必须有类（写错成 Maven group 会在这里失败）"
    dependsOn("assembleRelease")
    mappingFile.set(releaseR8Mapping)
    this.sharedPackagesFile.set(sharedPackagesSourceFile)
}

val verifyForbiddenPackages = tasks.register<VerifyForbiddenPackagesTask>("verifyForbiddenPackages") {
    group = "verification"
    description = "release dex 不得包含与系统同名的包或未使用的图标包"
    dependsOn("assembleRelease")
    mappingFile.set(releaseR8Mapping)
    forbiddenPackages.set(forbiddenPackageNames)
}

// ---------------------------------------------------------------------------
// Release signing gate. A release artifact is what plugin trust is pinned to, so the release build
// must be signed with the repository-external key: a missing keystore.properties fails the gate with
// the exact steps to fix it. `-PallowDebugSigning=true` is the only opt-out and exists for
// side-loading smoke runs and CI, neither of which has (or should have) the release key.
// ---------------------------------------------------------------------------

val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    group = "verification"
    description = "release 必须用仓库外的 keystore.properties 签名（本地侧载冒烟可用 -PallowDebugSigning=true 显式绕过）"
    keystorePropertiesPath.set(keystorePropertiesFile.absolutePath)
    repoRootPath.set(rootProject.projectDir.absolutePath)
    allowDebugSigning.set(allowDebugSigningProperty)
}

// The signing check reads two files, so it must run before R8: without a keystore the gate has to
// fail in seconds, not after a full minified release build.
tasks.matching { it.name == "assembleRelease" }.configureEach { mustRunAfter(verifyReleaseSigning) }

tasks.register("releaseGate") {
    group = "verification"
    description = "release 构建 + 发布密钥/共享包/禁忌包三重校验"
    dependsOn(verifyReleaseSigning, verifySharedPackagesInDex, verifyForbiddenPackages)
}
