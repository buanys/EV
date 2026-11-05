plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugins.template"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugins.template"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
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

// 宿主在运行时提供这些包；打进容器会造成类身份重复。
// 「不带 UnitTest」是有意的：单测跑在普通 JVM 上，需要真的 stdlib。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":loader"))

    testImplementation("junit:junit:4.13.2")
    // 契约里的 ServiceKey 在类初始化时就会建对象，JVM 单测的 classpath 也要有内核类型。
    testImplementation(project(":plugin-api"))
    testImplementation(project(":loader"))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}

/*
 * 需要用到 agent 契约（AgentService / ToolRegistry / SessionLog / ApprovalPolicy…）时，
 * 取消下面这段的注释，并在 dependencies 里加上 compileOnly(files(agentApiClasses))。
 *
 * 为什么不能写 project(":plugins:agent-api")：AGP 不允许 application 模块依赖另一个
 * application 模块，只能对着它的编译产物编译，运行时由 sidecar 的 dependencies 送进来。
 * 忘了写 sidecar 依赖，线上表现是 ClassNotFoundException 而不是编译错误。
 */
// val agentApiClasses = rootProject.file(
//     "plugins/agent-api/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
// )

val pluginDist = layout.buildDirectory.dir("dist")

// 用 project.name 推产物名：复制目录改名之后，这里不需要再改一次。
val moduleName = project.name

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/$moduleName-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("$moduleName.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce template.jar and template.json for EV/mods"
    dependsOn("bundlePluginJar")
    doLast {
        // sidecar 是运行时唯一能读到的元信息：id / 入口类 / 依赖 / 权限。
        // 这里写的就是插件真正声明的权限，别在代码里"顺便"用别的能力。
        val metadata = """
            {
              "id": "ev.buan.plugins.template",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugins.template.TemplatePlugin",
              "apiVersion": 1
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "$moduleName.json").writeText(metadata)
    }
}

// 所有插件的共同约束：configuration cache 不兼容声明 + 禁用 material-icons-extended 检查。
// 新插件一律 apply 这个脚本，不要把这些往模块里抄一份。
apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))

// agent-api 的 class jar 由另一个工程产出，clean build 时先有它才能编译这里。
// 没有用到 agent 契约就删掉这段。
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":plugins:agent-api:bundleDebugClassesToCompileJar")
}
