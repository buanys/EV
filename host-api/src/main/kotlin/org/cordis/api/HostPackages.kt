package org.cordis.api

/**
 * Package prefixes the host charges itself with.
 *
 * The plugin class loader resolves these from the host before consulting the plugin APK, so a
 * plugin can call Compose, Room, OkHttp and friends without packaging a second copy of them.
 * Two copies would split the class identity across the class-loader boundary and break every
 * `is`/`===` check the libraries rely on.
 *
 * Keep this list in sync with the `:host` dependencies; a library present here but absent from
 * the host fails at class-load time with [ClassNotFoundException].
 */
object HostPackages {
    /** Prefixes beyond the loader's built-ins (java, javax, android, kotlin, org.cordis). */
    val shared: Set<String> = linkedSetOf(
        // EV design system: plugin UI must inherit the host theme instead of shipping its own.
        "ev.buan.ui",
        "androidx.compose",
        "androidx.activity",
        "androidx.lifecycle",
        "androidx.navigationevent",
        "androidx.datastore",
        "androidx.room",
        "androidx.sqlite", // Room's public API exposes androidx.sqlite types
        "top.yukonga.miuix",
        // okhttp3, not com.squareup.okhttp3: the latter is a Maven group, not a package.
        "okhttp3",
        "okio",
        "kotlinx.serialization",
        "org.intellij.markdown",
        "org.jsoup",
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "org.luckypray.dexkit",
        "org.lsposed.hiddenapibypass",
        "io.github.libxposed",
        // 插件契约本身（ToolSpec、AgentService…）。这类原来靠 agent-api 这个空壳插件
        // 把 dex 塞进别的插件的 classpath；现在它们就在宿主 APK 里，走同一条共享包路径。
        "ev.buan.plugins.agent.api",
    )
}
