package ev.buan

import androidx.activity.enableEdgeToEdge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvPage
import ev.buan.ui.EvTheme
import ev.buan.ui.evSystemBarsPadding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A plugin's own full screen.
 *
 * A plugin cannot declare an Activity itself: the class is not in the host manifest, so
 * `startActivity` would fail with ActivityNotFoundException. This host-owned Activity is the
 * declared endpoint; it resolves the plugin by id and composes that plugin's UI full screen.
 *
 * ## 两种启动方式
 *
 * - **从宿主内部打开**（聊天页 → 菜单 → 浏览器）：进程已经全量启动，直接用。
 * - **从桌面快捷方式冷启动**：进程是新的，只加载这个插件和它的依赖闭包
 *   （[LaunchScope.Closure]）。用户点的是"浏览器"图标，他不需要聊天、模型、记忆。
 *
 * 第二种情况下这个 Activity 要**自己**把宿主跑起来。旧版在 runtime 为空时直接跳回
 * MainActivity，那是"没有插件页可显示"的恢复路径，会把快捷方式变成"打开聊天页"。
 */
class PluginPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val app = application as EvApplication

        setContent {
            var runtime by remember { mutableStateOf(app.startedRuntime) }
            var failed by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (runtime != null) return@LaunchedEffect
                runCatching { app.runtime.start(LaunchScope.Closure(pluginId)) }
                    .onFailure { failed = true }
                runtime = app.startedRuntime
                if (runtime == null) failed = true
            }

            if (failed) {
                // 启动失败（安全模式、装不上的容器）：交给主界面去解释，别停在一个空页上。
                LaunchedEffect(Unit) {
                    startActivity(
                        Intent(this@PluginPageActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    finish()
                }
                return@setContent
            }

            val live = runtime
            if (live == null) {
                LoadingScreen()
                return@setContent
            }

            // 最近任务里这张卡片的名字：用插件标题而不是应用名，
            // 否则桌面上的"浏览器"和应用切换里的"EV"看起来是两回事。
            LaunchedEffect(live) {
                val title = runCatching { live.titleFor(pluginId) }.getOrNull()
                if (!title.isNullOrBlank()) {
                    setTaskDescription(android.app.ActivityManager.TaskDescription(title))
                }
            }

            val page = live.pageFor(pluginId)
            val ui = live.uiFor(pluginId)

            EvTheme(
                appearance = live.appearance.value,
                spec = live.themeSpec(live.appearance.value.themeId),
            ) {
                if (page != null) {
                    // The page owns its chrome; the host only supplies the surface.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        page.Page(Modifier.fillMaxSize().evSystemBarsPadding(), onBack = { finish() })
                    }
                } else if (ui == null) {
                    EvPage(title = "EV") {
                        item {
                            ev.buan.ui.EvPreferenceGroup {
                                ev.buan.ui.EvPreferenceRow(title = "插件未就绪", summary = pluginId)
                            }
                        }
                    }
                } else {
                    EvPage(title = titleOf(pluginId), onBack = { finish() }) {
                        item {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                                ui.Content(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    /** 冷启动那几秒的占位：一句话，不转圈——转圈会让人以为卡住了。 */
    @androidx.compose.runtime.Composable
    private fun LoadingScreen() {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "正在加载插件…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    private fun titleOf(id: String): String = (application as EvApplication).runtime.titleFor(id)

    companion object {
        private const val EXTRA_PLUGIN_ID = "ev.plugin.id"

        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, PluginPageActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
