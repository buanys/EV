package ev.buan

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.evSystemBarsPadding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动的引导。
 *
 * 只讲三件真会影响你的事：EV 是什么、它为什么要那两个权限、插件放哪。
 * 不介绍功能——空壳没有功能可介绍，能力都在插件里，等装上了自然看见。
 *
 * 之前只有一个 [HostGate]：没存储权限时甩一句"去授权"。问题是它只说了一半——
 * 无障碍才是 EV 能动手的那一半，而这个权限在系统设置里藏得很深，没人会自己找到。
 */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    // 权限状态在回到前台时重新取：用户是去系统设置里点的，回来必须重新读一次，
    // 否则按钮会一直显示"去授权"，看起来像没生效。
    var storage by remember { mutableStateOf(hasAllFilesAccess()) }
    var accessibility by remember { mutableStateOf(EvAccessibilityService.hasAccess(context)) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        storage = hasAllFilesAccess()
        accessibility = EvAccessibilityService.hasAccess(context)
    }

    val tokens = MiuixTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.background)
            .evSystemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(260)) togetherWith fadeOut(tween(180))
                },
                label = "onboardingStep",
            ) { current ->
                Column(Modifier.fillMaxWidth()) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> PermissionStep(
                            storage = storage,
                            accessibility = accessibility,
                            onStorage = { context.openAllFilesSettings() },
                            onAccessibility = { EvAccessibilityService.openSettings(context) },
                        )
                        else -> ReadyStep()
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            StepDots(step = step, count = 3)
            Spacer(Modifier.height(20.dp))

            PrimaryAction(
                label = when {
                    step == 0 -> "继续"
                    step == 1 && (!storage || !accessibility) -> "稍后再说"
                    step == 1 -> "继续"
                    else -> "进入 EV"
                },
                onClick = {
                    if (step >= 2) onFinish() else step++
                },
                emphasized = step == 0 || (step == 1 && storage && accessibility),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun WelcomeStep() {
    val tokens = MiuixTheme.colorScheme
    Text(
        text = "EV",
        style = MiuixTheme.textStyles.title1,
        color = tokens.onBackground,
    )
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
    onStorage: () -> Unit,
    onAccessibility: () -> Unit,
) {
    val tokens = MiuixTheme.colorScheme
    Text(
        text = "还差两件事",
        style = MiuixTheme.textStyles.title1,
        color = tokens.onBackground,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "这两个权限决定了它能做什么，缺一个都是残的。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(24.dp))

    EvPreferenceGroup {
        EvPreferenceRow(
            title = if (storage) "共享存储 · 已授权" else "共享存储 · 去授权",
            summary = "插件放在 EV/mods，宿主必须能读这里。",
            onClick = if (storage) null else onStorage,
        )
        EvPreferenceRow(
            title = if (accessibility) "屏幕操作 · 已开启" else "屏幕操作 · 去开启",
            summary = "EV 靠它看屏幕、点按。开启后操作时会显示胶囊和边缘光，" +
                "你可以随时暂停或停止。",
            onClick = if (accessibility) null else onAccessibility,
        )
    }
}

@Composable
private fun ReadyStep() {
    val tokens = MiuixTheme.colorScheme
    Text(
        text = "准备好了",
        style = MiuixTheme.textStyles.title1,
        color = tokens.onBackground,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "如果首页还是空的，说明界面插件（ev-ui）没装。把 jar 放进 EV/mods，" +
            "在通知里点一次重启，或者从插件管理页重新扫描。",
        style = MiuixTheme.textStyles.body1,
        color = tokens.onSurfaceVariantSummary,
    )
}

/** 三颗点，当前那颗是实心的。比"1/3"轻，也比进度条安静。 */
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
 * 底部动作。
 *
 * 强调态用主题色实心，其余用一行可点文字——引导页只有"向前"和"跳过"两种动作，
 * 两个都做成实心按钮会让人不知道该按哪个。
 */
@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, emphasized: Boolean) {
    val tokens = MiuixTheme.colorScheme
    if (emphasized) {
        EvPreferenceGroup {
            EvPreferenceRow(title = label, onClick = onClick)
        }
    } else {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = tokens.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(tokens.surfaceContainerHighest.copy(alpha = 0.6f))
                .padding(vertical = 14.dp)
                .then(Modifier),
        )
    }
}
