package ev.buan.plugins.probe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.ui.DEFAULT_INTERFACE_SCALE
import ev.buan.ui.EvAccentColor
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvBackButton
import ev.buan.ui.EvCard
import ev.buan.ui.EvCardDefaults
import ev.buan.ui.EvGroupTitle
import ev.buan.ui.EvPaletteStyle
import ev.buan.ui.EvPreferenceDefaults
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvThemeMode
import ev.buan.ui.MAX_INTERFACE_SCALE
import ev.buan.ui.MIN_INTERFACE_SCALE
import ev.buan.ui.StatusIdle
import ev.buan.ui.StatusRunning
import ev.buan.ui.StatusSuccess
import ev.buan.ui.normalizeInterfaceScale
import kotlinx.coroutines.delay
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import org.cordis.api.AppearanceService
import org.cordis.api.HostPackages
import org.cordis.api.HostPermission
import org.cordis.api.PLUGIN_API_VERSION
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage
import org.cordis.dependencies
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * ABI probe.
 *
 * `hello` only exercises what the host itself already calls, so it cannot detect an R8 strip of
 * something that only plugins reach. This plugin deliberately touches the rest of the published
 * surface - defaults objects, enum entries, status colors, helpers, the back affordance - and
 * reports every entry independently, so a trimmed member shows up as one FAILED line instead of
 * hiding behind a working neighbour.
 */
class ProbePlugin : Plugin<Unit>, AgentSurface, AgentUi {
    override val name = "abi-probe"
    override val title = "ABI 探针"
    override val body = "逐个调用宿主未使用的公开 API，release 包能全绿才算 ABI 稳定。"

    override val inject: Dependencies =
        dependencies(AppearanceService.Key, PermissionService.Key, PluginStorage.Key)

    private val report = mutableListOf<String>()
    private var service: AppearanceService? = null
    private var permissions: PermissionService? = null
    private var storage: PluginStorage? = null

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        service = ctx.require(AppearanceService.Key)
        permissions = ctx.require(PermissionService.Key)
        storage = ctx.require(PluginStorage.Key)
        // A suspend hop makes the Continuation descriptor part of the runtime path, not just
        // the class file.
        delay(1)

        probe("PLUGIN_API_VERSION") { PLUGIN_API_VERSION }
        probe("HostPackages.shared") { HostPackages.shared.size }
        probe("AppearanceService.current") { service!!.current.themeMode.name }

        // Live proof that the host injected a per-plugin PermissionService and that the declared
        // PLUGIN_STORAGE grant is visible from inside the plugin. Activation itself is gated by the
        // same declaration, so this line only runs when the permission is granted.
        probe("PermissionService.isGranted(PLUGIN_STORAGE)") {
            permissions!!.isGranted(HostPermission.PLUGIN_STORAGE)
        }
        probe("PermissionService.require(PLUGIN_STORAGE)") {
            permissions!!.require(HostPermission.PLUGIN_STORAGE)
            "ok"
        }

        // Live proof that the host injected a per-plugin PluginStorage instance: the same relative
        // path must land in this plugin's own area, and the file must be readable back.
        probe("PluginStorage.write/read") {
            val path = "probe/last-run.txt"
            storage!!.write(path, "probe storage ok".encodeToByteArray())
            val text = storage!!.read(path)?.decodeToString()
            check(text == "probe storage ok") { "read=${text ?: "null"}" }
            "ok"
        }
        probe("PluginStorage.atomicWrite") {
            storage!!.atomicWrite("probe/atomic.txt", "atomic-ok".encodeToByteArray())
            storage!!.read("probe/atomic.txt")?.decodeToString()
        }
        probe("PluginStorage.list") { storage!!.list("probe").joinToString(",") }
        probe("PluginStorage.delete") { storage!!.delete("probe/atomic.txt") }

        probe("EvThemeMode.entries") { EvThemeMode.entries.joinToString(",") { it.name } }
        probe("EvPaletteStyle.entries") { EvPaletteStyle.entries.size }
        probe("EvAccentColor.entries") { EvAccentColor.entries.size }

        probe("EvCardDefaults.CornerRadius") { EvCardDefaults.CornerRadius.value }
        probe("EvPreferenceDefaults") {
            listOf(
                EvPreferenceDefaults.SidePadding,
                EvPreferenceDefaults.GroupSpacing,
                EvPreferenceDefaults.RowMinHeight,
                EvPreferenceDefaults.ContentStart,
            ).joinToString("/") { it.value.toString() }
        }

        probe("EvAppearance.normalized") { EvAppearance(interfaceScale = 99f).normalized().interfaceScale }
        probe("EvAppearance.copy") { EvAppearance().copy(monetEnabled = true, pureBlackEnabled = true).toString().length }
        probe("normalizeInterfaceScale") { normalizeInterfaceScale(0.1f) }
        probe("scaleBounds") { "$MIN_INTERFACE_SCALE/$DEFAULT_INTERFACE_SCALE/$MAX_INTERFACE_SCALE" }

        service!!.update { it.copy(blurEnabled = it.blurEnabled) }
        probe("AppearanceService.update") { "ok" }
    }

    private fun probe(name: String, block: () -> Any?) {
        report += runCatching { "$name = ${block()}" }
            .getOrElse { "$name ✗ ${it::class.simpleName}: ${it.message}" }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        EvGroupTitle("ABI 探针")
        EvPreferenceGroup(modifier = Modifier.fillMaxWidth()) {
            EvPreferenceRow(
                title = if (report.isEmpty()) "未执行" else "${report.count { " ✗ " in it }} 项失败 / ${report.size} 项",
                summary = "宿主 release 包逐项调用结果",
            )
        }
        EvCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column {
                report.forEach { line ->
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (" ✗ " in line) StatusIdle else StatusSuccess,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Text(
                    text = "composable 路径: EvCard / EvGroupTitle / StatusSuccess / StatusIdle / EvBackButton",
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusRunning,
                    modifier = Modifier.padding(top = 10.dp),
                )
                EvBackButton(onClick = {})
            }
        }
    }
}
