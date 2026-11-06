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
}
