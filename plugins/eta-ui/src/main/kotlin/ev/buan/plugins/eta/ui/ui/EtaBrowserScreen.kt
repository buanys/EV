package ev.buan.plugins.eta.ui.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A browser panel inside the chat page: one WebView with an address bar and the usual controls.
 *
 * A plain [Column] rather than [ev.buan.ui.EvPage], because the page slot of EvPage is a LazyColumn
 * and a WebView has to own the full remaining height and handle its own scrolling.
 *
 * Scope is deliberately small - this is "open a page and look at it", not a tabbed browser: links
 * stay in the panel, no external app is launched, and the WebView is destroyed with the panel so a
 * closed panel holds no page, no JS context and no timers. No permission is needed beyond the
 * host's own INTERNET.
 */
@Composable
fun EtaBrowserScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The WebView itself is not Compose state: the panels only need a handle, never a recomposition.
    val holder = remember { WebViewHolder() }
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    // While the field has focus the user is editing, so navigation must not overwrite what they type.
    var editingAddress by remember { mutableStateOf(false) }

    fun syncControls(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    fun open(input: String) {
        val view = holder.view ?: return
        val target = normalizeUrl(input) ?: return
        focusManager.clearFocus()
        keyboard?.hide()
        address = target
        view.loadUrl(target)
    }

    // System back walks the history first and only then leaves the panel; the chat page installs its
    // own handler for the settings/tools sheets, and this deeper one wins while the browser is open.
    BackHandler {
        val view = holder.view
        keyboard?.hide()
        if (view != null && view.canGoBack()) {
            view.goBack()
            syncControls(view)
        } else {
            onBack()
        }
    }

    // One WebView per panel composition, torn down when the panel closes. The clients are dropped
    // first: they capture composition state, and a late page callback after dispose would write it.
    // The view is detached before destroy() because a WebView still attached to its parent leaks.
    DisposableEffect(Unit) {
        onDispose {
            holder.view?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.webChromeClient = WebChromeClient()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
            holder.view = null
        }
    }

    Column(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundGlyphAction(EvGlyphKind.Send, "返回聊天", rotate = -90f, onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (address.isEmpty()) {
                    Text(
                        text = "输入网址",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onBackground, fontSize = 13.sp),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open(address) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { editingAddress = it.isFocused },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Go",
                style = MiuixTheme.textStyles.body1,
                color = if (address.isBlank()) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clickable(enabled = address.isNotBlank(), onClickLabel = "打开地址") { open(address) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundGlyphAction(EvGlyphKind.Send, "后退", rotate = -90f, enabled = canGoBack) {
                holder.view?.let { view ->
                    view.goBack()
                    syncControls(view)
                }
            }
            RoundGlyphAction(EvGlyphKind.Send, "前进", rotate = 90f, enabled = canGoForward) {
                holder.view?.let { view ->
                    view.goForward()
                    syncControls(view)
                }
            }
            RoundGlyphAction(EvGlyphKind.Refresh, "刷新", enabled = currentUrl.isNotEmpty()) {
                holder.view?.reload()
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    loading -> "载入中…"
                    pageTitle.isNotBlank() -> pageTitle
                    currentUrl.isNotBlank() -> currentUrl
                    else -> "输入网址开始浏览"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // JS and DOM storage make ordinary sites work; file/content access stays off
                        // so a page cannot read this app's private data through file:// URLs.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // Transparent so the panel's own background shows before the first page load.
                        setBackgroundColor(Color.TRANSPARENT)
                        webViewClient = object : WebViewClient() {
                            // Never hand a tap to an external browser: the panel is the browser.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean = false

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                                if (!url.isNullOrBlank()) currentUrl = url
                                syncControls(view)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    if (!editingAddress) address = url
                                }
                                pageTitle = view.title.orEmpty()
                                syncControls(view)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        holder.view = this
                    }
                },
            )
            if (currentUrl.isEmpty()) {
                Text(
                    text = "地址栏输入网址后按 Go；链接会留在本面板内打开。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                )
            }
        }
    }
}

/** Holds the platform view across recompositions without making it Compose state. */
private class WebViewHolder {
    var view: WebView? = null
}

@Composable
private fun RoundGlyphAction(
    glyph: EvGlyphKind,
    label: String,
    rotate: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvGlyph(
            glyph,
            color = if (enabled) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.onBackground.copy(alpha = 0.25f)
            },
            modifier = Modifier.size(17.dp).rotate(rotate),
        )
    }
}

/**
 * The address a user typed, as a WebView URL: a bare host gets `https://`, anything that already
 * names its scheme is left alone.
 *
 * "localhost:8080" is the reason this is a whitelist instead of "does it look like a scheme": that
 * string is a host and a port, not a `localhost:` scheme, so it must be prefixed too.
 */
internal fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
