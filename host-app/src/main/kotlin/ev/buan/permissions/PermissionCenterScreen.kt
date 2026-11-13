package ev.buan.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ev.buan.ui.EvPage
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import ev.buan.ui.EvTextKind
import ev.buan.ui.EvTone

/**
 * 权限中心：**还差什么**排在最前面，已授予的收在下面。
 *
 * 这一页存在的理由不是"列清单"，而是：agent 能不能动手，取决于这几项；
 * 缺哪项就会在哪一步失败，而失败现场通常在别的 app 里，用户无从判断。
 * 所以状态必须现读（刚从系统设置回来就要变），而不是进页面时算一次。
 */
@Composable
fun PermissionCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 每次回到前台重读：用户多半是刚授权完返回的。
    var entries by remember { mutableStateOf(PermissionProbe.read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) entries = PermissionProbe.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    EvPage(title = "权限中心", onBack = onBack) {
        val missing = entries.filter { !it.granted }
        val granted = entries.filter { it.granted }

        if (missing.isEmpty()) {
            EvSection(title = "全部就绪") {
                EvRow(
                    title = "该给的都给了",
                    summary = "EV 现在能完整地动手。",
                    trailing = { StatusPill("已就绪", EvTone.Accent) },
                )
            }
        } else {
            EvSection(
                title = "还差 ${missing.size} 项",
                footnote = "点一行去系统设置里打开；回来这一页会自动刷新。",
            ) {
                missing.forEach { PermissionRow(it, context) }
            }
        }

        if (granted.isNotEmpty()) {
            EvSection(title = "已授予") {
                granted.forEach { PermissionRow(it, context) }
            }
        }
    }
}

@Composable
private fun PermissionRow(entry: PermissionEntry, context: android.content.Context) {
    EvRow(
        title = entry.title,
        summary = entry.summary,
        tone = if (entry.granted) EvTone.Normal else EvTone.Warning,
        onClick = entry.target?.let { target ->
            { context.startActivity(PermissionProbe.intent(context, target)) }
        },
        trailing = {
            StatusPill(
                if (entry.granted) "已开启" else "去开启",
                if (entry.granted) EvTone.Muted else EvTone.Accent,
            )
        },
    )
}

@Composable
private fun StatusPill(text: String, tone: EvTone) {
    EvText(text, kind = EvTextKind.Caption, tone = tone)
}
