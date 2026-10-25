import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
    kotlin("android") version "2.4.20" apply false
    kotlin("plugin.sam.with.receiver") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

allprojects {
    group = "io.github.meteor149"
    version = "0.0.1-SNAPSHOT"
}

// Kernel libraries are multiplatform. The Android host, the host-only core, the host/plugin
// contract and the plugin APKs are not, and must not receive the KMP + Android library plugins.
val kernelModules = subprojects.filter {
    it.name != "packager" &&
        it.path != ":host" &&
        it.path != ":host-core" &&
        it.path != ":plugin-api" &&
        it.path != ":ui-kit" &&
        !it.path.startsWith(":plugins")
}

configure(kernelModules) {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "com.android.kotlin.multiplatform.library")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        jvmToolchain(21)
        jvm {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                javaParameters.set(true)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        js(IR) {
            nodejs()
            binaries.library()
        }
        iosArm64()
        iosSimulatorArm64()
        macosArm64()
        applyDefaultHierarchyTemplate()

        sourceSets {
            commonTest.dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
            jvmTest {
                dependencies {
                    implementation(kotlin("test-junit5"))
                    implementation("org.junit.jupiter:junit-jupiter:5.12.2")
                }
            }
        }

        // AGP registers the KMP Android library extension on the Kotlin extension,
        // so it is reached through ExtensionAware rather than the project container.
        (this as org.gradle.api.plugins.ExtensionAware).extensions
            .configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
                namespace = "org.cordis.${project.name.replace('-', '.')}"
                compileSdk = 36
                minSdk = 26
                // AGP 9 does not create Android test source sets unless asked. Without this the
                // androidHostTest sources exist on disk but no task ever compiles or runs them.
                withHostTest {}
            }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

// Only the kernel libraries, `:plugin-api` and `:ui-kit` are published. The host and its internal
// core are application-internal, and plugins are packaged as .kplugin archives, not artifacts.
val publishableModules = subprojects.filter {
    it.path != ":host" && it.path != ":host-core" && !it.path.startsWith(":plugins")
}

configure(publishableModules) {
    apply(plugin = "com.vanniktech.maven.publish")

    val publicationGroup = group.toString()
    val publicationArtifactId = name
    val publicationVersion = version.toString()

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(publicationGroup, publicationArtifactId, publicationVersion)

        pom {
            name.set("Cordis Kotlin - $publicationArtifactId")
            description.set(
                if (project.name == "packager") {
                    "A Gradle plugin for packaging Cordis JVM and Android plugins as .kplugin archives."
                } else {
                    "A Kotlin Multiplatform implementation of Cordis."
                },
            )
            inceptionYear.set("2021")
            url.set("https://github.com/meteor149/cordis-kotlin")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("meteor149")
                    name.set("meteor149")
                    url.set("https://github.com/meteor149")
                }
            }

            scm {
                url.set("https://github.com/meteor149/cordis-kotlin")
                connection.set("scm:git:https://github.com/meteor149/cordis-kotlin.git")
                developerConnection.set("scm:git:ssh://git@github.com/meteor149/cordis-kotlin.git")
            }
        }
    }
}

// `:host-core` is a JVM module, so its tests are the plain `test` task rather than `jvmTest`.
tasks.register("test") {
    group = "verification"
    dependsOn(kernelModules.map { "${it.path}:jvmTest" } + "packager:test" + ":host-core:test")
}
