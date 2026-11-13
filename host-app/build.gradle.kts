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

// 应用版本只有这一份：defaultConfig 与 verifyReleaseVersion 都读它。
// 发版流程：改这里（versionName + versionCode 一起）→ 打 tag v<versionName> → 推。
val appVersionName = "0.1.0"
val appVersionCode = 1

android {
    namespace = "ev.buan"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
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
    implementation(project(":kernel:core"))
    implementation(project(":kernel:loader"))
    implementation(project(":ui-kit"))
    implementation(project(":host-api"))
    // Host-only logic that must not be published as part of the plugin contract (see :host-core).
    implementation(project(":host-core"))
    implementation(project(":host-runtime"))

    // Shared runtime the host lends to every plugin. Plugins compileOnly these coordinates and
    // must not package them, or the class identity would split across the class-loader boundary.
    // Versions follow EV's catalog so a migrated plugin needs no second copy.
    implementation(libs.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.core)
    implementation(libs.navigationevent.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.jetbrains:markdown:0.7.16")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // org.json must come from the platform: bundling it shadows core-libart and R8 inlining
    // then references fields the platform class does not have.
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // host-app 是组装层：真正可测的逻辑住在 :host-runtime 与 :host-core，它们的单测
    // 也就在那两个模块里（2026-11-06 从本模块搬走，解掉 internal 跨模块不可见的问题）。
}

// 内置 seed 插件已移除（实验插件 hello 于 2026-10-25 连同其他实验插件一并删除）。
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

// 发布 tag 必须与 versionName 一致（tag 决定 Release 名字与资产文件名，versionName 决定设备上
// 显示与升级判定；两份手写事实，漂过一次）。没有 tag 时只警告——本地 releaseGate 不该因为它变红。
val verifyReleaseVersion = tasks.register<ev.buan.gradle.VerifyReleaseVersionTask>("verifyReleaseVersion") {
    group = "verification"
    description = "发布 tag == v+versionName，且 versionCode ≥ 1"
    expectedTag.set(
        providers.environmentVariable("EV_RELEASE_TAG")
            .orElse(providers.gradleProperty("evReleaseTag"))
            .orElse(""),
    )
    versionName.set(appVersionName)
    versionCode.set(appVersionCode)
}

tasks.register("releaseGate") {
    group = "verification"
    description = "release 构建 + 发布密钥/共享包/禁忌包/版本一致性四重校验"
    dependsOn(verifyReleaseSigning, verifySharedPackagesInDex, verifyForbiddenPackages, verifyReleaseVersion)
}
