import ev.buan.gradle.ApiSnapshotTask
import ev.buan.gradle.VerifyApiSnapshotTask

// SDK public API snapshots — offline fallback for binary-compatibility-validator.
//
// The validator's Gradle plugin is not in the local dependency cache (the build must work offline),
// so this applies the javap-based fallback instead: render every public/protected declaration of
// the module's compiled classes into one ordered text file and compare it with the committed
// baseline in the root `api/` directory. The snapshot changes only when the ABI changes.
//
// Applied by :plugin-api and :ui-kit. `./gradlew :plugin-api:apiSnapshot :ui-kit:apiSnapshot`
// refreshes the baselines; `apiSnapshotCheck` is the CI gate and fails on any difference.

val apiClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val apiBaselineDir = rootProject.layout.projectDirectory.dir("api")

tasks.register<ApiSnapshotTask>("apiSnapshot") {
    group = "verification"
    description = "Write api/${project.name}.txt from the compiled public API of :${project.name}."
    dependsOn("compileDebugKotlin")
    classes.from(apiClasses)
    moduleName.set(project.name)
    outputFile.set(apiBaselineDir.file("${project.name}.txt"))
}

tasks.register<VerifyApiSnapshotTask>("apiSnapshotCheck") {
    group = "verification"
    description = "Fail when the public API of :${project.name} differs from the committed api/${project.name}.txt."
    dependsOn("compileDebugKotlin")
    classes.from(apiClasses)
    moduleName.set(project.name)
    expectedFile.set(apiBaselineDir.file("${project.name}.txt"))
    // `apiSnapshot` writes the very file this task reads; when both are requested in one invocation
    // (local refresh-and-verify) the baseline must be written first. The check still verifies a
    // *committed* baseline in CI, where only this task runs.
    mustRunAfter("apiSnapshot")
}
