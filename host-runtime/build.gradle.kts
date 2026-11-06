plugins {
    id("com.android.library")
}

android {
    namespace = "ev.buan.runtime"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 契约（AgentPage / AgentUi / AgentSurface）与内核加载器；运行时由 :host 提供同一份实例。
    implementation(project(":host-api"))
    implementation(project(":loader"))
    // HostState 里的能力面/失败原因用到了 host-core 的判定类型。
    implementation(project(":host-core"))

    // 执行预算的语义全是协程语义（拒绝、超时、取消传播），没有真机也测得出来。
    // kotlin-test 与 host-app 时期一致：这批测试原本就写在 kotlin.test 上。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
