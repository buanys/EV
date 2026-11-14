plugins {
    id("com.android.library")
    // 必须开：`UiSlotRegistryImpl.contribute` 的参数是 `@Composable () -> Unit`。
    // 不开这个插件，编译器把它当普通 lambda 编成 Function0，而 host-api 的接口
    // （开了 compose）编成 Function2——两边 Kotlin 类型相同、JVM 描述符不同，
    // 编译能过，运行时就是 AbstractMethodError。
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ev.buan.runtime"
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
    // 契约（AgentPage / AgentUi / AgentSurface）与内核加载器；运行时由 :host 提供同一份实例。
    implementation(project(":host-api"))
    implementation(project(":kernel:loader"))
    // HostState 里的能力面/失败原因用到了 host-core 的判定类型。
    implementation(project(":host-core"))

    // 执行预算的语义全是协程语义（拒绝、超时、取消传播），没有真机也测得出来。
    // kotlin-test 与 host-app 时期一致：这批测试原本就写在 kotlin.test 上。
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    // 单测里的 org.json 是 android.jar 的 stub（方法体抛 "not mocked"）。凭据与扫描缓存都真的
    // 用它做编解码，所以给测试 classpath 一个真实现——只在 test 配置里，不进 APK。
    testImplementation(libs.json)
}
