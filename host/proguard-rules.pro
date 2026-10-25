# ---------------------------------------------------------------------------
# Obfuscation must stay off.
#
# The contract is not a list of classes - it is every class that can appear in a signature the
# plugin calls: kotlin.coroutines.Continuation (suspend functions), androidx.compose.runtime.Composer
# and Modifier (composable functions), Color, Dp, and whatever else leaks through. Renaming any of
# them changes the method descriptor, the host dex no longer matches the plugin dex, and the plugin
# dies with AbstractMethodError at load time. Keeping that transitive closure is unmaintainable,
# so the host ships a stable ABI instead. Shrinking and optimization stay enabled.
-dontobfuscate

# ---------------------------------------------------------------------------
# Host <-> plugin contract.
#
# Plugins are compiled against these names and resolved at runtime through the shared class
# loader. R8 renaming any of them turns every plugin into a NoClassDefFoundError / NoSuchMethod
# at load time, so the whole contract surface is pinned.
# ---------------------------------------------------------------------------

# Kernel API plugins call: Plugin, Context, ServiceKey, EffectScope, Dependencies, ...
-keep class org.cordis.** { *; }

# Host <-> plugin business types: AgentSurface, AgentUi, HostPackages, AppearanceService.
-keep class ev.buan.api.** { *; }
-keep class org.cordis.api.** { *; }

# Design system plugins compose with: EvTheme, EvCard, EvPage, EvPreference*, EvAppearance.
-keep class ev.buan.ui.** { *; }

# ---------------------------------------------------------------------------
# The shared runtime must be COMPLETE, not just un-renamed.
#
# Shrinking removes every member the host does not itself call, and a plugin may call any of them.
# Kotlin emits checkNotNullParameter at the top of nearly every function, so the first victim is
# kotlin.jvm.internal.Intrinsics; the same applies to Compose APIs the host never composes with
# (TextField, LazyColumn, ...) and to anything else the contract lets plugins reach.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.annotation.** { *; }
-keep class top.yukonga.miuix.** { *; }

# Every package in HostPackages.shared needs its own keep: shared only decides which copy the
# class loader resolves, it says nothing about whether the members still exist after shrinking.
-keep class androidx.navigationevent.** { *; }
-keep class androidx.datastore.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.intellij.markdown.** { *; }
-keep class org.jsoup.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class io.github.libxposed.** { *; }

# Plugins implement interfaces by reference; keep the implementations' signatures resolvable.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# commons-compress keeps optional codec bridges whose dependencies we deliberately do not ship.
# The host never touches those paths and a plugin cannot: the classes are absent at runtime.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

-dontwarn org.jetbrains.annotations.**
