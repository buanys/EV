package ev.buan.plugins.ping.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.plugins.ping.api.PingService
import ev.buan.ui.EvCard
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Consumer of a contract that lives in another plugin.
 *
 * [PingService] arrives through the dependency loader; the service instance arrives through Cordis.
 * A missing provider must degrade to "not ready", never to a crash, so the lookup is nullable.
 */
class PingUiPlugin : Plugin<Unit>, AgentSurface, AgentUi {
    override val name = "ping-ui"
    override val title = "跨插件契约探针"
    override val body = "从 ping-impl 取 PingService 并显示结果。"

    private var result: String = "未就绪：尚未查询"
    private var provided: Boolean = false

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val service = ctx.require(ServiceDirectory.Key).lookup(PingService.Key)
        provided = service != null
        result = service?.ping()?.let { "impl 返回：$it" } ?: "未就绪：PingService 未被提供"
    }

    @Composable
    override fun Content(modifier: Modifier) {
        EvPreferenceGroup(modifier = Modifier.fillMaxWidth()) {
            EvPreferenceRow(
                title = if (provided) "契约解析成功" else "契约未就绪",
                summary = result,
            )
        }
        EvCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                Text(
                    text = "PingService 定义在 ping-api 容器里，",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "本插件通过 descriptor.dependencies 拿到它。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
