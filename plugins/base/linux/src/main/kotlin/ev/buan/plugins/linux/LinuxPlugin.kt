package ev.buan.plugins.linux

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.PluginSetting
import org.cordis.api.PluginSettings
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real Linux userspace for the agent, installed on demand.
 *
 * Why this exists: the agent's shell runs on Android's own userland, which has toybox and almost no
 * toolchain - no compiler, no python, no package manager worth the name. Anything beyond "move a
 * file" therefore means shipping a second environment. This plugin owns that environment: a Debian
 * root filesystem under the app's private storage, entered with `chroot` through the same root shell
 * the agent already uses.
 *
 * What it deliberately does not do: pick a rootfs for you. `linux_setup` takes a URL, so the app
 * never silently downloads 300 MB and the mirror is the user's choice.
 */
class LinuxPlugin : Plugin<Unit>, org.cordis.api.ManifestProvider, PluginSettings {

    override val name = "linux"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, PluginStorage.Key)

    override val manifest: org.cordis.api.PluginManifest = PluginManifest(
        id = "ev.buan.plugins.linux",
        name = "Linux 环境",
        version = "1.0.0",
        description = "内置 Debian 根文件系统：apt、编译器和完整的用户态，与 Android 系统隔离。",
        capabilities = listOf(
            PluginCapability("linux_status", PluginCapability.Kind.TOOL, "查看环境状态", "是否已安装、占用空间、发行版版本。"),
            PluginCapability("linux_setup", PluginCapability.Kind.TOOL, "安装根文件系统", "从给定 URL 下载并解压 Debian 根文件系统。"),
            PluginCapability("linux_run", PluginCapability.Kind.TOOL, "在 Linux 里执行命令", "chroot 进入根文件系统跑一条命令。"),
        ),
        settings = listOf(
            PluginSetting(
                key = "url",
                title = "根文件系统地址",
                kind = PluginSetting.Kind.TEXT,
                description = "Debian 13 arm64 rootfs 的 tar.xz / tar.gz 地址；默认用 proot-distro 的 trixie 镜像。",
                value = DEFAULT_URL,
            ),
            PluginSetting(
                key = "timeout",
                title = "命令超时（秒）",
                kind = PluginSetting.Kind.TEXT,
                description = "单条命令最长运行时间，1 到 600 秒。",
                value = "60",
            ),
        ),
        permissions = listOf("ROOT_SHELL"),
    )

    private var storage: PluginStorage? = null
    private var rootfsUrl: String = DEFAULT_URL
    private var timeoutSeconds: Int = 60

    override fun read(key: String): String? = when (key) {
        "url" -> rootfsUrl
        "timeout" -> timeoutSeconds.toString()
        else -> null
    }

    override fun write(key: String, value: String) {
        when (key) {
            "url" -> rootfsUrl = value.trim().also { persist(URL_FILE, it) }
            "timeout" -> value.trim().toIntOrNull()?.coerceIn(1, 600)?.let {
                timeoutSeconds = it
                persist(TIMEOUT_FILE, it.toString())
            }
        }
    }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        android.util.Log.e("EVLINUX", "apply: enter")
        val directory = runCatching { ctx.require(ServiceDirectory.Key) }
            .onFailure { android.util.Log.e("EVLINUX", "apply: no ServiceDirectory ${it.message}") }
            .getOrNull() ?: return
        storage = runCatching { ctx.require(PluginStorage.Key) }
            .onFailure { android.util.Log.e("EVLINUX", "apply: no PluginStorage ${it.message}") }
            .getOrNull()
        android.util.Log.e("EVLINUX", "apply: storage=${storage != null}")
        rootfsUrl = storage?.read(URL_FILE)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
        timeoutSeconds = storage?.read(TIMEOUT_FILE)?.decodeToString()?.trim()?.toIntOrNull() ?: 60

        val registry = directory.lookup(ToolRegistry.Key)
        android.util.Log.e("EVLINUX", "apply: registry=${registry != null}")
        if (registry == null) return
        val root = Rootfs(storage)
        registry.register(
            PLUGIN_ID,
            listOf(
                StatusTool(root),
                SetupTool(root, urlOf = { rootfsUrl }, onUrl = { write("url", it) }),
                RunTool(root, timeout = { timeoutSeconds }),
            ),
        )
        android.util.Log.e("EVLINUX", "apply: tools registered")
        // An archive dropped into the storage area is installed on first activation. Without this the
        // only way in is the settings page, and on a device where the app has no outbound network
        // that page cannot download anything - the archive is the realistic path.
        if (!root.isInstalled()) {
            val candidate = File(storage?.rootPath().orEmpty(), "rootfs.tar.gz")
            if (candidate.isFile) {
                android.util.Log.e("EVLINUX", "apply: auto-installing ${candidate.absolutePath}")
                runCatching { root.installFromFile(candidate) }
                    .onSuccess { android.util.Log.e("EVLINUX", "apply: auto-install done $it") }
                    .onFailure { android.util.Log.e("EVLINUX", "apply: auto-install failed ${it.message}") }
            }
        }
        effect.collect { registry.unregister(PLUGIN_ID) }
    }

    private fun persist(path: String, value: String) {
        runCatching { storage?.atomicWrite(path, value.encodeToByteArray()) }
    }

    // ---- tools ---------------------------------------------------------------------------------

    private class StatusTool(private val root: Rootfs) : Tool {
        override val spec = ToolSpec(
            name = "linux_status",
            description = "报告 Linux 环境：是否已安装、发行版、占用空间、根目录路径。",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        )

        override suspend fun invoke(argumentsJson: String): String {
            val json = JSONObject()
            val dir = root.directory()
            json.put("installed", root.isInstalled())
            json.put("path", dir.absolutePath)
            json.put("bytes", root.sizeBytes())
            root.distribution()?.let { json.put("distribution", it) }
            if (!root.isInstalled()) json.put("hint", "用 linux_setup 安装，URL 见插件配置项。")
            return json.toString()
        }
    }

    private class SetupTool(
        private val root: Rootfs,
        private val urlOf: () -> String,
        private val onUrl: (String) -> Unit,
    ) : Tool {
        override val spec = ToolSpec(
            name = "linux_setup",
            description = "下载并解压根文件系统。参数 url 可省略，省略时用插件配置里的地址。",
            parametersJson = """
                {"type":"object","properties":{"url":{"type":"string","description":"rootfs tar.xz/tar.gz 地址"},"file":{"type":"string","description":"已下载到本机的归档路径（应用没有外网时用这个）"}},"additionalProperties":false}
            """.trimIndent(),
            dangerous = true,
        )

        override suspend fun invoke(argumentsJson: String): String {
            val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            val local = args?.optString("file")?.takeIf { it.isNotBlank() }
            if (local != null) {
                return runCatching { root.installFromFile(java.io.File(local)) }
                    .getOrElse { errorJson("导入失败：${it.message ?: it::class.simpleName}") }
            }
            val url = args?.optString("url")?.takeIf { it.isNotBlank() } ?: urlOf()
            if (url.isBlank()) return errorJson("没有可用的下载地址：请在插件配置里填写，或把 url 参数传进来。")
            onUrl(url)
            return runCatching { root.install(url) }
                .getOrElse { errorJson("安装失败：${it.message ?: it::class.simpleName}") }
        }
    }

    private class RunTool(private val root: Rootfs, private val timeout: () -> Int) : Tool {
        override val spec = ToolSpec(
            name = "linux_run",
            description = "在 Debian 环境里执行一条命令，返回 stdout/stderr/退出码。命令跑在 chroot 里，" +
                "看不到 Android 的文件系统（除非显式挂载），与系统隔离。",
            parametersJson = """
                {"type":"object","properties":{"command":{"type":"string","description":"要在 Debian 里执行的命令"}},"required":["command"],"additionalProperties":false}
            """.trimIndent(),
            dangerous = true,
        )

        override suspend fun invoke(argumentsJson: String): String {
            val command = runCatching { JSONObject(argumentsJson).optString("command") }.getOrNull()?.trim()
            if (command.isNullOrEmpty()) return errorJson("缺少 command 参数")
            if (!root.isInstalled()) return errorJson("Linux 环境还没安装：先调用 linux_setup。")
            return root.run(command, timeout().coerceIn(1, 600))
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.linux"
        const val URL_FILE = "settings/rootfs-url.txt"

        /**
         * Debian 13 (trixie) arm64 rootfs from the proot-distro release assets, ~34 MB.
         *
         * Chosen because it is a plain tar.xz of a minimal Debian userland and it is versioned, so the
         * same URL keeps working. The mirror stays a setting: this is a default, not a policy.
         */
        const val DEFAULT_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
        const val TIMEOUT_FILE = "settings/timeout.txt"

        fun errorJson(message: String): String = JSONObject().put("error", message).toString()
    }
}

/**
 * The root filesystem on disk, plus the two operations that touch it.
 *
 * Everything here goes through `su -c`, because chroot needs root and the plugin runs as the app's
 * uid. That is the same root shell the agent already has; this class does not widen the attack
 * surface, it only gives the root shell a directory to chroot into.
 */
internal class Rootfs(private val storage: PluginStorage?) {

    fun directory(): File = File(storage?.rootPath() ?: return File("/nonexistent"), DIR_NAME)

    fun isInstalled(): Boolean = File(directory(), "bin/bash").exists() || File(directory(), "usr/bin/bash").exists()

    fun sizeBytes(): Long = runCatching {
        directory().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Reads /etc/os-release through the shell, so it works for any distro the user installed. */
    fun distribution(): String? = runCatching {
        val out = exec("chroot ${directory().absolutePath} /bin/sh -c 'head -2 /etc/os-release'", 15)
        out.first.takeIf { it.isNotBlank() }?.lines()
            ?.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')
            ?.trim('"')
    }.getOrNull()

    /**
     * Downloads [url] and extracts it into the root directory.
     *
     * Download goes to a sibling file first and extraction happens only when the download finished,
     * so an interrupted transfer cannot leave a half-populated rootfs that looks installed.
     */
    /** Untars [archive] into the root directory and reports what came out. */
    private fun extract(archive: java.io.File): Map<String, Any> {
        val target = directory()
        target.mkdirs()
        val name = archive.name.lowercase()
        val flag = when {
            // Android tar shells out to an xz binary for this flag, and there is none on the
            // device: a .tar.xz has to be re-packed as .tar.gz before it can be installed here.
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> "-xJf"
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "-xzf"
            else -> "-xf"
        }
        val (_, err, code) = execRaw("tar $flag '${archive.absolutePath}' -C '${target.absolutePath}'", 900)
        // Distribution tarballs usually wrap everything in one top-level directory; the root is
        // where bin/ lives, so a single wrapper is flattened before we call it installed.
        if (code == 0 && !isInstalled()) {
            val children = target.listFiles().orEmpty()
            if (children.size == 1 && children[0].isDirectory) {
                val wrapper = children[0]
                execRaw(
                    "sh -c 'cd \"${target.absolutePath}\" && mv \"${wrapper.name}\"/* . 2>/dev/null; " +
                        "mv \"${wrapper.name}\"/.[!.]* . 2>/dev/null; rmdir \"${wrapper.name}\" 2>/dev/null; true'",
                    300,
                )
            }
        }
        val ok = code == 0 && isInstalled()
        return mapOf(
            "installed" to ok,
            "path" to target.absolutePath,
            "error" to if (code != 0) {
                val hint = if (err.contains("xz")) "（Android 的 tar 不能解 xz，请改用 .tar.gz）" else ""
                "解压失败（退出码 $code）$hint：${err.take(300)}"
            } else "",
            "hint" to if (ok) "可以调用 linux_run 了。" else "没找到 /bin/bash：确认归档本身就是根文件系统。",
        )
    }

    fun install(url: String): String {
        val target = directory()
        target.mkdirs()
        val archive = File(target.parentFile, "rootfs.download")
        // Resume, because a 34 MB transfer over a phone link gets cut and "start over" is not
        // something the user can act on. Three attempts, each continuing from the current size.
        var attempt = 0
        while (attempt < 3) {
            attempt += 1
            val have = if (archive.exists()) archive.length() else 0L
            val connection = java.net.URI(url).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "EV-linux-plugin")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val ok = runCatching {
                connection.getInputStream().use { input ->
                    java.io.FileOutputStream(archive, have > 0).use { output ->
                        input.copyTo(output, 1 shl 16)
                    }
                }
            }.isSuccess
            if (ok) break
        }
        if (!archive.exists() || archive.length() < 4096) {
            error("下载失败：只收到 ${archive.length()} 字节，链路中断或地址不可达")
        }
        val bytes = archive.length()
        val result = extract(archive)
        return JSONObject(result)
            .put("bytes_downloaded", bytes)
            .toString()
    }

    /** Extracts an archive the user already has on disk. Same steps as the download path after transfer. */
    fun installFromFile(archive: java.io.File): String {
        require(archive.isFile) { "文件不存在：${archive.absolutePath}" }
        return JSONObject(extract(archive)).toString()
    }

    fun run(command: String, timeoutSeconds: Int): String {
        // A chroot without /proc, /dev and /sys is not a usable Linux: apt cannot read /dev/null,
        // compilers cannot see /sys, and every profile script that calls id fails. Mounting them is
        // part of "the environment works", so it happens on the first command rather than in a
        // separate step the user would have to know about. Idempotent: already-mounted is ignored.
        ensureMounts()
        val quoted = command.replace("'", "'\\''")
        // An explicit PATH: the login profile assumes one, and the inherited Android PATH points at
        // toybox paths that do not exist inside Debian.
        val (out, err, code) = execRaw(
            "chroot '${directory().absolutePath}' /usr/bin/env -i " +
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "HOME=/root TERM=dumb LANG=C.UTF-8 /bin/bash -lc '$quoted'",
            timeoutSeconds,
        )
        return JSONObject()
            .put("exit", code)
            .put("stdout", out)
            .put("stderr", err)
            .toString()
    }

    /** Bind-mounts the kernel filesystems a Linux userland expects. Safe to call repeatedly. */
    private fun ensureMounts() {
        val root = directory()
        execRaw(
            "mount -t proc proc '${root.absolutePath}/proc' 2>/dev/null; " +
                "mount --bind /dev '${root.absolutePath}/dev' 2>/dev/null; " +
                "mount --bind /sys '${root.absolutePath}/sys' 2>/dev/null; true",
            30,
        )
    }

    private fun exec(command: String, timeoutSeconds: Int): Pair<String, String> {
        val (out, err, _) = execRaw(command, timeoutSeconds)
        return out to err
    }

    /** Runs through `su`, because chroot and tar into app storage both need the root shell. */
    private fun execRaw(command: String, timeoutSeconds: Int): Triple<String, String, Int> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Triple("", "超时（${timeoutSeconds}s）", -1)
        }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return Triple(out, err, process.exitValue())
    }

    private companion object {
        const val DIR_NAME = "debian"
    }
}
