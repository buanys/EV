# SDK public API snapshots

`plugin-api.txt` and `ui-kit.txt` are the committed public API surface of the two published SDK
modules. They are generated, never edited by hand.

- Regenerate after an intended API change: `./gradlew :plugin-api:apiSnapshot :ui-kit:apiSnapshot`
- Verify (what CI runs): `./gradlew :plugin-api:apiSnapshotCheck :ui-kit:apiSnapshotCheck`

Both files are rendered from the compiled classes of the module's `compileDebugKotlin` output with
`javap -protected -s` (see `gradle/api-snapshot.gradle.kts` and `buildSrc/src/main/java/ev/buan/gradle/`).
binary-compatibility-validator is the preferred tool, but this build must work offline and its Gradle
plugin is not in the local dependency cache, so javap is the fallback.

What is included: every public/protected class, field and method of the module plus its JVM
descriptor. Compiler-generated noise that no plugin can call is filtered out: Compose
`ComposableSingletons$*` holders, enum `$WhenMappings` tables, anonymous classes, Kotlin synthetic
`access$...` members and `*$default(...)` bridges.

A change to either file is an API change: it must be committed together with the code change, and
the reviewer should treat the diff as part of the contract review. `apiSnapshotCheck` fails when the
working tree's API differs from these baselines, including a baseline file that is missing.
