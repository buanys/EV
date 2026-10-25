package ev.buan

import androidx.activity.enableEdgeToEdge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import ev.buan.ui.EvPage
import ev.buan.ui.EvTheme
import ev.buan.ui.evSystemBarsPadding

/**
 * A plugin's own full screen.
 *
 * A plugin cannot declare an Activity itself: the class is not in the host manifest, so
 * `startActivity` would fail with ActivityNotFoundException. This host-owned Activity is the
 * declared endpoint; it resolves the plugin by id and composes that plugin's UI full screen.
 *
 * The loader's `CordisProxyActivity` goes further - it instantiates a plugin-declared Activity and
 * attaches the plugin APK's own resource table. That is only needed once plugins ship their own
 * XML resources; Compose-only plugins do not need it.
 */
class PluginPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        // Recovery path: the system may restore this Activity in a fresh process, where no plugin
        // has been loaded - and a safe-mode launch loads none by design. Rather than showing an
        // empty page, go back to the host screen, which runs a full sync and can reopen the plugin.
        // `startedRuntime` is non-null only after a start pass finished outside safe mode, so this
        // branch also keeps a page from rendering before any plugin exists.
        val runtime = (application as EvApplication).startedRuntime
        if (runtime == null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }
        val page = runtime.pageFor(pluginId)
        val ui = runtime.uiFor(pluginId)

        setContent {
            EvTheme(appearance = runtime?.appearance?.value ?: ev.buan.ui.EvAppearance()) {
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

    private fun titleOf(id: String): String = (application as EvApplication).runtime.titleFor(id)

    companion object {
        private const val EXTRA_PLUGIN_ID = "ev.plugin.id"

        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, PluginPageActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
