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
import ev.buan.intro.LaunchIntro
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

            val live = runtime
            if (live == null) {
                // 和主界面同一套启动动画，不是另一套"正在加载插件…"。
                // 它自己负责调 start(scope) 并处理权限/失败，所以这里不再另起一个
                // LaunchedEffect——两条路径同时启动会互相踩。
                LaunchIntro(
                    runtime = app.runtime,
                    activity = this@PluginPageActivity,
                    scope = LaunchScope.Closure(pluginId),
                ) {
                    runtime = app.startedRuntime
                }
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
                        page.Page(Modifier.fillMaxSize().evSystemBarsPadding(), onBack = { leave() })
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
                    EvPage(title = titleOf(pluginId), onBack = { leave() }) {
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

    /**
     * 离开这个页面。
     *
     * 从桌面快捷方式打开时它是一个独立任务（`isTaskRoot`），这时候**不能 finish**：
     * Activity 一销毁任务就空了，最近任务里那张卡也随之消失，用户再想切回来就没入口。
     * 移到后台才是"退出应用"的正确语义——任务还在，点回来是原样。
     *
     * 从宿主内部打开时它不是根，`finish()` 才是回去的路。
     */
    private fun leave() {
        if (isTaskRoot) {
            moveTaskToBack(true)
        } else {
            finish()
        }
    }

    private fun titleOf(id: String): String = (application as EvApplication).runtime.titleFor(id)

    companion object {
        private const val EXTRA_PLUGIN_ID = "ev.plugin.id"

        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, PluginPageActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
