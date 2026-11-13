plugins {
    `java`
}

// Release-gate task classes live here instead of in build script action closures: a `doLast { }`
// closure captures the script object, which the configuration cache cannot serialize. Standalone
// task classes with declared inputs keep the build configuration-cache compatible.
//
// buildSrc is build tooling, so it depends on the Gradle API only: no third-party library, no
// plugin, nothing to resolve from a repository. That keeps `gradlew` working on a fresh checkout
// (and offline) and guarantees the task classes are compiled against the very Gradle that runs them.
//
// The task classes live in package `ev.buan.gradle`, not `ev.buan.build`: `.gitignore` ignores every
// `build/` directory, so a Java package called `build` would make its sources uncommittable.
dependencies {
    implementation(gradleApi())
}

// The failure messages are Chinese; pin UTF-8 so they read the same on every platform (Windows
// would otherwise use the OEM code page).
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Bytecode level matches the Android host (compileOptions = 17) and stays loadable by the JVM that
// runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
