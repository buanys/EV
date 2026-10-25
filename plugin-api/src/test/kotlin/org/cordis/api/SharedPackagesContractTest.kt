package org.cordis.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the single source of truth: gradle/shared-packages.txt.
 *
 * A shared package is only real if all three views agree. Missing any one of them already produced
 * a production failure once: `com.squareup.okhttp3` was listed as a package (it is a Maven group),
 * compiled fine, and left every plugin unable to reach OkHttp.
 *
 * The dex-side assertion lives in :host:verifySharedPackagesInDex, because mapping.txt only exists
 * after a release build.
 */
class SharedPackagesContractTest {

    private val root: File = sequenceOf(".", "..", "../..")
        .map { File(it).absoluteFile }
        .firstOrNull { File(it, "gradle/shared-packages.txt").isFile }
        ?: error("找不到仓库根目录（从 ${File(".").absolutePath} 向上找 gradle/shared-packages.txt）")

    private val declared: List<String> = File(root, "gradle/shared-packages.txt").readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }

    private val hostPackages: String =
        File(root, "plugin-api/src/main/kotlin/org/cordis/api/HostPackages.kt").readText()

    private val proguard: String = File(root, "host/proguard-rules.pro").readText()

    @Test
    fun `清单无重复项`() {
        assertEquals(declared.size, declared.distinct().size, "shared-packages.txt 有重复项")
    }

    @Test
    fun `HostPackages 与清单完全一致`() {
        val inSource = Regex("^\\s*\"([^\"]+)\"", RegexOption.MULTILINE).findAll(hostPackages)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(declared.toSet(), inSource, "HostPackages.kt 与 shared-packages.txt 漂移")
    }

    @Test
    fun `每个共享包都有对应的 keep 规则`() {
        val missing = declared.filter { "-keep class $it.**" !in proguard }
        assertTrue(missing.isEmpty(), "以下共享包缺少 proguard keep（会在 release 中被裁剪）：$missing")
    }

    @Test
    fun `清单项看起来是包名而不是 Maven group`() {
        val suspicious = declared.filter { p ->
            p.any { it.isUpperCase() } || p.split('.').any { it.isEmpty() }
        }
        assertTrue(suspicious.isEmpty(), "以下清单项不是合法包名：$suspicious")
    }
}
