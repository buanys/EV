import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.forge.compiler"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.forge.compiler"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 宿主运行时提供，打进容器会造成类身份重复。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

/**
 * 载荷（约 26 MB / 11 dex + 5 个 jar）不进源码树，也不该进 git：
 * `scripts/forge-payload.sh` 负责搬运或（P1b 之后）从源码重建，产物落在本模块的 `payload/`。
 *
 * 覆盖来源：`-PforgeProbeJar=/path/to/compileprobe.jar` 或环境变量 `FORGE_PROBE_JAR`。
 * 决策与待办见 docs/adr/0004-on-device-forge.md。
 */
val payloadDir = layout.projectDirectory.dir("payload")

val sdkDir: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "/opt/android-sdk"
}

dependencies {
    compileOnly(project(":host-api"))
    compileOnly(project(":loader"))

    // 编译期只需要编译器与 d8 的符号：类本体由载荷在运行时提供（容器里的 dex），
    // 所以两者都是 compileOnly，不进 RuntimeClasspath，也就不会重复打包。
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    compileOnly(files("$sdkDir/build-tools/37.0.0/lib/d8.jar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation("org.json:json:20260814")
}

val probeJar = providers.gradleProperty("forgeProbeJar")
    .orElse(providers.environmentVariable("FORGE_PROBE_JAR"))
    .orElse("/sdcard/EV/mods/compileprobe.jar")

val preparePayload = tasks.register<Exec>("prepareCompilerPayload") {
    group = "distribution"
    description = "把编译器载荷搬进 payload/（Tier 1：从已验证的探针产物搬运）"
    val out = payloadDir.asFile
    // 契约 jar 取当前构建产物：探针里的 plugin-api 冻结在 2026-10-27，没有后续新增的契约。
    val pluginApiJar = rootProject.file("plugin-api/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar")
    dependsOn(":plugin-api:bundleLibCompileToJarDebug")
    commandLine(
        "bash", rootProject.file("scripts/forge-payload.sh").absolutePath,
        "--from-probe", probeJar.get(),
        "--plugin-api", pluginApiJar.absolutePath,
        "--out", out.absolutePath,
    )
    outputs.dir(out)
    // 载荷已在就不必每次解 30 MB；要重搬就删掉 payload/ 再跑。
    onlyIf { !File(out, "payload.json").isFile }
}

val bundlePluginJar = tasks.register("bundlePluginJar") {
    group = "distribution"
    description = "拼出 forge-compiler.jar：自己的 dex + 载荷 dex + 载荷资源"
    dependsOn("assembleDebug", preparePayload)
    val dist = layout.buildDirectory.dir("dist")
    val apk = layout.buildDirectory.file("outputs/apk/debug/forge-compiler-debug.apk")
    outputs.file(dist.map { it.file("forge-compiler.jar") })
    val payload = payloadDir
    doLast {
        val out = File(dist.get().asFile, "forge-compiler.jar")
        out.parentFile.mkdirs()

        fun payloadDexFiles(): List<File> = File(payload.asFile, "dex")
            .listFiles { f -> f.extension == "dex" }
            .orEmpty()
            .sortedWith(compareBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 })

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            // 1. 本插件自己的类排在最前（dex 装载顺序 = 覆盖顺序）。
            var ownDex = 0
            ZipFile(apk.get().asFile).use { apkZip ->
                val entries = apkZip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .sortedBy { it.name }
                    .toList()
                entries.forEachIndexed { index, entry ->
                    put(if (index == 0) "classes.dex" else "classes${index + 1}.dex", apkZip.getInputStream(entry).readBytes())
                }
                ownDex = entries.size
            }

            // 2. 载荷 dex 接在后面，序号连续。
            check(ownDex > 0) { "assembleDebug 没有产出 classes.dex" }
            payloadDexFiles().forEachIndexed { index, dex ->
                put("classes${ownDex + index + 1}.dex", dex.readBytes())
            }

            // 3. 载荷资源：编译期 classpath 的 jar + 编译器启动要读的清单。
            File(payload.asFile, "lib").listFiles().orEmpty().sortedBy { it.name }.forEach {
                put("lib/${it.name}", it.readBytes())
            }
            val metaRoot = File(payload.asFile, "META-INF")
            metaRoot.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                val rel = file.relativeTo(metaRoot).path.replace(File.separatorChar, '/')
                put("META-INF/$rel", file.readBytes())
            }
        }
    }
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce forge-compiler.jar and forge-compiler.json for EV/mods"
    dependsOn(bundlePluginJar)
    val distDir = layout.buildDirectory.dir("dist")
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugins.forge.compiler",
              "version": "1.0.1",
              "entryClass": "ev.buan.plugins.forge.compiler.ForgeCompilerPlugin",
              "apiVersion": 1,

              "dependencies": []
            }
        """.trimIndent()
        File(distDir.get().asFile, "forge-compiler.json").writeText(metadata)
    }
}

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
