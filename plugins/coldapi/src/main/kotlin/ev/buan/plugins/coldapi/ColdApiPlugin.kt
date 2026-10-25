package ev.buan.plugins.coldapi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvCard
import ev.buan.ui.EvGroupTitle
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.StatusSuccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import org.jsoup.Jsoup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

/**
 * Cold-API probe.
 *
 * `probe` covers ui-kit surface the host never calls. This one covers the *libraries*: OkHttp,
 * Jsoup, coroutines primitives and Compose drawing, none of which the host itself uses. If R8
 * trims a member here, a working `probe` still passes and this plugin fails - which is exactly the
 * blind spot that produced the `kotlin.jvm.internal.Intrinsics` crash.
 *
 * No network: OkHttp only builds a request, Jsoup only parses a literal.
 */
class ColdApiPlugin : Plugin<Unit>, AgentSurface, AgentUi {
    override val name = "cold-api-probe"
    override val title = "冷门 API 探针"
    override val body = "调用宿主未使用的 OkHttp / Jsoup / coroutines / Compose 绘图 API。"

    private val report = mutableListOf<String>()

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        probe("OkHttpClient.Builder") {
            OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build().connectTimeoutMillis
        }
        probe("okhttp3.Request") {
            Request.Builder().url("https://example.invalid/").header("X-EV", "1").build().method
        }
        probe("jsoup.parse") { Jsoup.parse("<p>ev</p>").select("p").text() }
        probe("Mutex.withLock") {
            val mutex = Mutex()
            mutex.withLock { "locked" }
        }
        probe("Channel") {
            val channel = Channel<Int>(1)
            channel.trySend(7).isSuccess
            channel.receive()
        }
        probe("Flow.toList") { flowOf(1, 2, 3).toList().sum() }
        probe("withTimeoutOrNull") { withTimeoutOrNull(50) { 42 } }
        probe("Collections") { listOf(3, 1, 2).sortedBy { it } }
    }

    private suspend fun probe(name: String, block: suspend () -> Any?) {
        report += runCatching { "$name = ${block()}" }
            .getOrElse { "$name ✗ ${it::class.simpleName}: ${it.message}" }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        EvGroupTitle("冷门 API 探针")
        EvPreferenceGroup(modifier = Modifier.fillMaxWidth()) {
            EvPreferenceRow(
                title = if (report.isEmpty()) "未执行" else "${report.count { " ✗ " in it }} 项失败 / ${report.size} 项",
                summary = "宿主从未调用过的库 API",
            )
        }
        EvCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                report.forEach { line ->
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (" ✗ " in line) MiuixTheme.colorScheme.error else StatusSuccess,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                // Compose drawing path: host never draws a Canvas.
                Canvas(Modifier.size(40.dp).padding(top = 8.dp)) {
                    drawCircle(color = Color(0xFF7CE3B4), radius = size.minDimension / 3f)
                }
            }
        }
    }
}
