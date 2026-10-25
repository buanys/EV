# Cordis Compose Multiplatform Plugin Lab

> **已冻结（2026-10-24）**：本目录是独立 Gradle 构建，钉在 Kotlin 2.1.21 / AGP 8.7.2 / Compose 1.8.2，
> 与主线（Kotlin 2.4.20 / AGP 9.4.1）失配。本期架构加固不升级、不迁移它，也不把它纳入主线 CI
> （[.github/workflows/android.yml](../.github/workflows/android.yml) 只构建根工程）。它作为历史实验
> 保留；需要重新启用时，先整体升级到主线版本再接入 CI。现状说明见
> [docs/architecture.md §7.4](../docs/architecture.md)。

This standalone demo uses one Compose Multiplatform UI to exercise the real Cordis plugin
runtime on Desktop JVM and Android. `composeApp` owns the shared UI and platform runtimes;
`android` is intentionally only the installable Android shell.

## Layout

```text
demo/
├── composeApp/          shared Compose UI + JVM/Android runtime adapters
├── android/             Android manifest, launcher Activity, APK asset packaging
├── plugin-api/          host-owned API shared across class-loader boundaries
└── plugins/
    ├── desktop/         isolated plugin and support JARs
    └── android/         isolated plugin APKs and preview Activities
```

The build includes the repository root as a composite build, so the lab always tests the current
`core`, `loader`, and `hmr` source instead of a previously published binary.

## Experiments

| Control | Desktop JVM | Android |
| --- | --- | --- |
| Forest | isolated child-first JAR and plugin-owned resource | isolated APK, dex, resources, and Activity |
| Ocean | declared dependency on `palette.jar` | declared dependency on `palette.apk` |
| Sunset | checksummed private support JAR | isolated plugin resources and proxy Activity |
| Install Forest v2 | transactional fresh `URLClassLoader` generation | transactional fresh `DexClassLoader` generation |
| Probe rollback | broken JAR fails during apply; v2 remains active | broken APK fails during apply; v2 remains active |

The timer belongs to the host Compose UI. It deliberately keeps running while plugin generations
are replaced, making host state continuity visible. The signal rail records effect application and
disposal, entry activation, resource/component routing, commits, errors, and rollbacks.

## Run

From the repository root:

```powershell
.\gradlew.bat -p demo :composeApp:run
```

For Android, open `demo` as the Gradle project in Android Studio and run the `android` application,
or build the APK from the command line:

```powershell
.\gradlew.bat -p demo :android:assembleDebug
```

The shell APK is written to `demo/android/build/outputs/apk/debug/` and embeds the debug plugin
APKs under `assets/plugins/android/`. Desktop plugin JARs are generated and embedded automatically
before the desktop resource task runs.

## Verify

```powershell
.\gradlew.bat -p demo :composeApp:desktopTest
.\gradlew.bat -p demo :android:assembleDebugAndroidTest
```

`DesktopPluginLabRuntimeTest` runs the complete dependency, private-classpath, resource, reload,
and rollback sequence. `AndroidPluginLabInstrumentedTest` runs the APK dependency, reload, and
rollback sequence on a device or emulator; the Android UI also exposes proxy Activity routing for
each active plugin.

If the Android SDK is not discoverable, set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or add an ignored
`demo/local.properties` containing `sdk.dir=...`.
