kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":kernel:core"))
        implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    }
}
