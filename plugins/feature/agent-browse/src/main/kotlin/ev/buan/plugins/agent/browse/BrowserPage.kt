package ev.buan.plugins.agent.browse

import android.Manifest
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个浏览器面板：把标签条、顶栏、**宿主的 WebView**、错误页和空态拼在一起。
 *
 * 这个文件只负责**组装**——状态在 [BrowserPanel]，顶栏在 [BrowserChrome]，
 * 标签条在 [BrowserTabStrip]，历史栏在 [BrowserBottomBar]，WebView 本身归宿主
 * （[BrowserPanel.surface]）。
 *
 * 用普通 [Column] 而不是 `EvPage`：EvPage 的页面槽是 LazyColumn，而 WebView 需要占满
 * 剩余高度并自己处理滚动。
 *
 * 这里唯一"重"的一段是文件选择与运行时权限：它们都是"发出去 → 等 Activity 回来"的
 * 两段式，回调必须先存在组合里（也只有组合拿得到 launcher），所以由页面提供、
 * 由 [BrowserPanel] 转交给宿主。
 */
@Composable
internal fun BrowserPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    panel: BrowserPanel,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    // 一个窗口都没有时先开一个：面板总得有一页可看，否则是一块空白。
    LaunchedEffect(Unit) { panel.ensureTab() }

    val pendingFile = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingPermission = remember { mutableStateOf<PermissionRequest?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFile.value
        pendingFile.value = null
        // parseResult 在取消时返回 null——这正好是 WebView 要求的那句"用户没选"。
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    val permissionAsker = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val request = pendingPermission.value
        pendingPermission.value = null
        if (request == null) return@rememberLauncherForActivityResult
        val resources = request.resources
        val refused = resources.any { resource ->
            androidPermissionFor(resource)?.let { granted[it] != true } == true
        }
        // 页面可能已经跳走了，这时的 grant/deny 会抛；吞掉比崩掉好。
        runCatching { if (refused) request.deny() else request.grant(resources) }
    }

    DisposableEffect(Unit) {
        panel.filePicker = { callback, params ->
            // 上一次没回的必须先结掉：WebView 同一时刻只允许一个文件选择请求。
            pendingFile.value?.onReceiveValue(null)
            pendingFile.value = callback
            runCatching {
                filePicker.launch(params.createIntent())
                true
            }.getOrElse {
                // 选择器都没起来：这次请求就当用户取消，否则这个页面再也弹不出来。
                pendingFile.value = null
                callback.onReceiveValue(null)
                false
            }
        }
        panel.permissionAsker = { request, permissions ->
            pendingPermission.value = request
            permissionAsker.launch(permissions)
        }
        panel.attach()
        onDispose {
            panel.filePicker = null
            panel.permissionAsker = null
            // 只放下视图：窗口与页面留在宿主里，下次进来接着看同一页。
            panel.release()
        }
    }

    val tab = panel.active

    // 系统返回先走网页历史，没有历史才离开面板。
    BackHandler {
        keyboard?.hide()
        if (tab?.canGoBack == true) panel.back() else onBack()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding()) {
            BrowserTabStrip(panel = panel)
            BrowserChrome(panel = panel, onBack = onBack)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 宿主的容器：所有窗口的 WebView 都长在里面，只有当前窗口可见。
                // 同一个容器在"离开页面"时被搬回屏幕外，回来时再搬进来——View 只有一个，
                // 页面也只有一份。
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { panel.surface() },
                    update = {},
                )

                if (tab == null || tab.url.isEmpty()) {
                    BrowserHome(
                        panel = panel,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background),
                    )
                }

                tab?.error?.let { message ->
                    ErrorView(
                        message = message,
                        onRetry = { panel.reload() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            BrowserBottomBar(panel = panel)
        }

        // 全屏视频：盖住整块面板的黑色层，页面给的视图挂进去。用单独的一层而不是挪 WebView，
        // 是为了让网页的位置与尺寸不变——挪它会触发重新排版，退出全屏时看着像跳了一下。
        panel.fullscreenView?.let { player ->
            FullscreenLayer(player = player, onLeave = { panel.leaveFullscreen() })
        }
    }
}

/** 网页视频全屏时的那一层。 */
@Composable
private fun FullscreenLayer(player: View, onLeave: () -> Unit) {
    BackHandler { onLeave() }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { context -> FrameLayout(context) },
        update = { host ->
            if (player.parent !== host) {
                (player.parent as? ViewGroup)?.removeView(player)
                host.addView(player, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        },
    )
}

/** 网页资源名 → Android 运行时权限；不相干的（如 DRM）返回 null，表示不需要系统权限。 */
private fun androidPermissionFor(resource: String): String? = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
    else -> null
}

/**
 * 加载失败时的页面。
 *
 * 旧版没有这个回调：断网或者域名打不开时就是一片白，用户不知道是网络、地址写错，
 * 还是应用坏了。这里至少要说清楚是哪一种。
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "打不开这个页面",
            style = MiuixTheme.textStyles.subtitle,
            color = colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.primary.copy(alpha = 0.12f))
                .clickable(onClickLabel = "重试", onClick = onRetry)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                text = "重试",
                style = MiuixTheme.textStyles.body1,
                color = colors.primary,
            )
        }
    }
}
