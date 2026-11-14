package ev.buan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ev.buan.chat.ChatState
import ev.buan.chat.EvApprovalDialog
import ev.buan.chat.EvChatScreen
import ev.buan.intro.PaperStartup
import ev.buan.files.FileManagerScreen
import ev.buan.permissions.PermissionCenterScreen
import ev.buan.plugins.PluginManagerScreen
import ev.buan.settings.SettingsScreen
import ev.buan.agent.AgentScreen
import ev.buan.ui.EvTheme
import org.cordis.api.UiSlot
import ev.buan.ui.evSystemBarsPadding

/**
 * 应用入口。
 *
 * 现在只有一条路径：**启动序列 → 对话页**。
 *
 * 引导、设置、插件管理、插件详情五屏已整体归档
 * （`/workspace/EV-archive/2026-11-10-screens/`），等重新设计。
 * 所以这里没有任何 `when` 分支去判断"该显示哪一屏"——只有一个页面，
 * 判断本身就是多余的。
 *
 * 审批浮层仍然留在最外层：它属于"任何页面都可能出现"的东西，
 * 不属于某一屏。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 没有这一行，WindowInsets.systemBars 与 ime 都读 0，
        // 内容和输入框会跑到状态栏与键盘下面去。
        enableEdgeToEdge()
        val runtime = (application as EvApplication).runtime
        HostSlots.provider = { runtime.slotRegistry() }

        setContent {
            var introDone by rememberSaveable { mutableStateOf(false) }
            EvTheme(
                appearance = runtime.appearance.value,
                spec = runtime.themeSpec(runtime.appearance.value.themeId),
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (!introDone) {
                        PaperStartup(
                            runtime = runtime,
                            onGrant = { openAllFilesSettings(this@MainActivity) },
                            onDone = { introDone = true },
                        )
                    } else {
                        val scope = rememberCoroutineScope()
                        val chat = remember(runtime) {
                            ChatState(
                                services = { runtime.services },
                                ensure = { item -> runtime.ensureProvider(item) },
                                scope = scope,
                            )
                        }
                        LaunchedEffect(runtime.state.value) { chat.refresh() }
                        // 只有一个退出点：每屏的返回都回 chat。不引入导航库——
                        // 现在只有三个目的地，加一个 NavController 是给自己造抽象。
                        var screen by rememberSaveable { mutableStateOf("chat") }
                        Box(Modifier.fillMaxSize().evSystemBarsPadding()) {
                            when (screen) {
                                "plugins" -> PluginManagerScreen(
                                    cards = runtime.state.value?.cards.orEmpty(),
                                    onBack = { screen = "chat" },
                                    onToggle = { id, enabled ->
                                        scope.launch { runtime.setEnabled(id, enabled) }
                                    },
                                    // 直连宿主的安装入口：安装服务走 cordis.provide，UI 手上那份
                                    // ServiceDirectory 是另一条注册链，查不到它。
                                    onInstall = { jar, sidecar -> runtime.install(jar, sidecar) },
                                    onUninstall = { id -> runtime.uninstall(id) },
                                    onRollback = { id -> runtime.rollback(id); Unit },
                                    canRollback = { id -> runtime.hasRollback(id) },
                                    onResetFailures = { id -> runtime.resetFailureScore(id) },
                                )

                                "agent" -> AgentScreen(
                                    services = runtime.services,
                                    onBack = { screen = "chat" },
                                )

                                "files" -> FileManagerScreen(
                                    onBack = { screen = "chat" },
                                )

                                "permissions" -> PermissionCenterScreen(
                                    onBack = { screen = "chat" },
                                )

                                "settings" -> SettingsScreen(
                                    appearance = runtime.appearance.value,
                                    onThemeMode = { mode ->
                                        runtime.updateAppearance { copy(themeMode = mode) }
                                    },
                                    onPureBlack = { enabled ->
                                        runtime.updateAppearance { copy(pureBlackEnabled = enabled) }
                                    },
                                    onBlur = { enabled ->
                                        runtime.updateAppearance { copy(blurEnabled = enabled) }
                                    },
                                    onBack = { screen = "chat" },
                                )

                                else -> EvChatScreen(
                                    state = chat,
                                    onOpenSettings = { screen = "settings" },
                                    onOpenPlugins = { screen = "plugins" },
                                    onOpenAgent = { screen = "agent" },
                                    onOpenPermissions = { screen = "permissions" },
                                    onOpenFiles = { screen = "files" },
                                )
                            }
                        }
                    }

                    EvApprovalDialog(
                        pending = runtime.approval.pending,
                        onDecide = runtime.approval::decide,
                    )
                }
            }
                    SlotHost(UiSlot.GlobalOverlay)
        }
    }
}
