package ev.buan

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.evSystemBarsPadding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动的引导。
 *
 * 只讲三件真会影响你的事：EV 是什么、它拿着哪些权限做什么、插件放哪。
 * 不介绍功能——空壳没有功能可介绍，能力都在插件里，等装上了自然看见。
 *
 * 权限按"一类事"分组，不按系统弹窗的顺序：用户看不懂 READ_SMS 和 READ_CALL_LOG 的区别，
 * 但看得懂"谁给你发的消息、谁给你打过电话"。
 */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    // 权限状态在回到前台时重读：用户是去系统设置里点的，回来必须重新读一次，
    // 否则按钮一直显示"去授权"，看起来像没生效。
    var storage by remember { mutableStateOf(hasAllFilesAccess()) }
    var accessibility by remember { mutableStateOf(EvAccessibilityService.hasAccess(context)) }
    var snapshot by remember { mutableStateOf(scanPermissions(context)) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        storage = hasAllFilesAccess()
        accessibility = EvAccessibilityService.hasAccess(context)
        snapshot = scanPermissions(context)
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 不看返回的 map：用户在系统弹窗里逐项点，最终以系统状态为准。
        snapshot = scanPermissions(context)
    }

    val tokens = MiuixTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.background)
            .evSystemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(56.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                label = "onboardingStep",
            ) { current ->
                Column(Modifier.fillMaxWidth()) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> PermissionStep(
                            storage = storage,
                            accessibility = accessibility,
                            snapshot = snapshot,
                            onStorage = { context.openAllFilesSettings() },
                            onAccessibility = { EvAccessibilityService.openSettings(context) },
                            onGroup = { group -> requestPermissions.launch(group.toPermissions()) },
                        )
                        else -> ReadyStep()
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            StepDots(step = step, count = 3)
            Spacer(Modifier.height(18.dp))

            val allCoreReady = storage && accessibility
            PrimaryAction(
                label = when {
                    step == 0 -> "继续"
                    step == 1 && !allCoreReady -> "稍后再说"
                    step == 1 -> "继续"
                    else -> "进入 EV"
                },
                onClick = { if (step >= 2) onFinish() else step++ },
                emphasized = step == 0 || (step == 1 && allCoreReady),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WelcomeStep() {
    val tokens = MiuixTheme.colorScheme
    Text("EV", style = MiuixTheme.textStyles.title1, color = tokens.onBackground)
    Spacer(Modifier.height(12.dp))
    Text(
        text = "一个能动手操作你手机的应用",
        style = MiuixTheme.textStyles.subtitle,
        color = tokens.onBackground,
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = "它的能力全部来自插件：读屏、点按、文件、网页、终端。宿主本身只做三件事——" +
            "加载插件、发布服务、把结果交回给你。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = "所以第一次打开时它几乎是空的。这不是坏了，是还没装东西。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
}

@Composable
private fun PermissionStep(
    storage: Boolean,
    accessibility: Boolean,
    snapshot: PermissionSnapshot,
    onStorage: () -> Unit,
    onAccessibility: () -> Unit,
    onGroup: (PermissionGroup) -> Unit,
) {
    val tokens = MiuixTheme.colorScheme
    Text("把这些交给它", style = MiuixTheme.textStyles.title1, color = tokens.onBackground)
    Spacer(Modifier.height(10.dp))
    Text(
        text = "每一项都对应一类具体的事。不给也能跑，但插件只能靠 root 绕路，" +
            "而且每次都要你点同意。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(20.dp))

    EvPreferenceGroup {
        EvPreferenceRow(
            title = if (storage) "共享存储 · 已授权" else "共享存储 · 去授权",
            summary = "插件放在 EV/mods，宿主必须能读这里。",
            onClick = if (storage) null else onStorage,
        )
        EvPreferenceRow(
            title = if (accessibility) "屏幕操作 · 已开启" else "屏幕操作 · 去开启",
            summary = "看屏幕、点按。开启后操作时会显示胶囊和边缘光，随时可暂停。",
            onClick = if (accessibility) null else onAccessibility,
        )
    }
    Spacer(Modifier.height(14.dp))
    EvPreferenceGroup {
        PermissionGroup.entries.forEach { group ->
            val done = snapshot.isDone(group)
            EvPreferenceRow(
                title = "${group.title} · ${if (done) "已授权" else "去授权"}",
                summary = group.summary,
                onClick = if (done) null else ({ onGroup(group) }),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "这些现在给也行，之后在设置里随时能改。一次不给全，插件就得多走一次 root，" +
            "多等你点一次同意。",
        style = MiuixTheme.textStyles.footnote1,
        color = tokens.onSurfaceVariantSummary.copy(alpha = 0.7f),
    )
}

@Composable
private fun ReadyStep() {
    val tokens = MiuixTheme.colorScheme
    Text("准备好了", style = MiuixTheme.textStyles.title1, color = tokens.onBackground)
    Spacer(Modifier.height(12.dp))
    Text(
        text = "如果首页还是空的，说明界面插件（ev-ui）没装。把 jar 放进 EV/mods，" +
            "在通知里点一次重启，或者从插件管理页重新扫描。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
}

@Composable
private fun StepDots(step: Int, count: Int) {
    val tokens = MiuixTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val active = index == step
            Box(
                Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) tokens.primary else tokens.onSurfaceVariantSummary.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

/**
 * 底部动作。强调态用一整个可点分组，其余用一行安静的文字——
 * 引导页只有"向前"和"稍后"两种动作，两个都做成实心按钮会让人不知道该按哪个。
 */
@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, emphasized: Boolean) {
    val tokens = MiuixTheme.colorScheme
    if (emphasized) {
        EvPreferenceGroup {
            EvPreferenceRow(
                title = label,
                onClick = onClick,
            )
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                // 原来是 onSurfaceVariantSummary + 0.55 的底，读起来像禁用状态。
                // 它是个正常的"跳过"，该有正常文字的对比度。
                .background(tokens.surfaceContainerHighest.copy(alpha = 0.8f))
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1,
                color = tokens.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- 权限分组

/**
 * 按"一类事"分组，不按系统权限名。
 *
 * 系统弹窗里 `READ_SMS` 和 `READ_CALL_LOG` 是两个陌生的名字，而"谁给你发过消息、
 * 谁给你打过电话"是一句人话。引导页要讲人话。
 */
internal enum class PermissionGroup(
    val title: String,
    val summary: String,
    val permissions: List<String>,
) {
    PERSONAL(
        title = "个人数据",
        summary = "谁给你发过消息、谁打过电话、今天有什么会。只读，不会改。",
        permissions = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
        ),
    ),
    LOCATION(
        title = "位置与运动",
        summary = "你在哪、附近有什么、今天走了多少步。",
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
        ),
    ),
    SENSORS(
        title = "相机与麦克风",
        summary = "看图、听声音。只在插件主动调用时使用，系统会亮指示器。",
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ),
    ),
    MEDIA(
        title = "相册与视频",
        summary = "按类型翻媒体库，而不是只知道一个文件路径。",
        permissions = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        ),
    ),
    ;

    fun toPermissions(): Array<String> = permissions.toTypedArray()
}

/** 一次扫描的结果：哪几组已经全部拿到。只存结果，不存逐项状态——引导页不需要那么细。 */
internal class PermissionSnapshot(private val done: Set<PermissionGroup>) {
    fun isDone(group: PermissionGroup): Boolean = group in done
}

internal fun scanPermissions(context: Context): PermissionSnapshot {
    val done = PermissionGroup.entries.filter { group ->
        group.permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }.toSet()
    return PermissionSnapshot(done)
}

/**
 * 系统级开关是否已经打开——使用情况、精确闹钟、装 APK。
 *
 * 这几个不在引导页里逐项列（太深了，用户第一次打开不该被九个开关淹没），
 * 但值得留一个查询入口，设置页或以后的"能力体检"会用上。
 */
internal object SpecialAccess {

    fun usageStats(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            manager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun exactAlarm(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
    }.getOrDefault(false)

    fun installPackages(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)
}
